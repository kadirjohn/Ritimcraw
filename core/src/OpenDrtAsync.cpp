// Asynchronous OpenDRT bake manager (F1-8e); see OpenDrtAsync.h for the contract.

#include <rawedit/OpenDrtAsync.h>

namespace rawedit {

namespace {
bool sameSpec(const OpenDrtSpec& a, const OpenDrtSpec& b) {
    return a.look == b.look && a.tonescale == b.tonescale && a.cwp == b.cwp && a.cwpLimit == b.cwpLimit &&
           a.grey == b.grey && a.display == b.display;
}
OpenDrtAsyncBaker::Backend defaultBackend() {
    OpenDrtAsyncBaker::Backend b;
    b.bake = [](const OpenDrtSpec& s, const std::atomic<bool>& c) { return bakedOpenDrtLutCancellable(s, c); };
    b.peek = [](const OpenDrtSpec& s) { return peekBakedOpenDrtLut(s); };
    return b;
}
} // namespace

OpenDrtAsyncBaker::OpenDrtAsyncBaker(std::function<void()> onReady)
    : OpenDrtAsyncBaker(defaultBackend(), std::move(onReady)) {}

OpenDrtAsyncBaker::OpenDrtAsyncBaker(Backend backend, std::function<void()> onReady)
    : backend_(std::move(backend)), onReady_(std::move(onReady)) {
    thread_ = std::thread([this] { run(); });
}

OpenDrtAsyncBaker::~OpenDrtAsyncBaker() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        stop_ = true;
        if (inFlightCancel_) inFlightCancel_->store(true);
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void OpenDrtAsyncBaker::setOnReady(std::function<void()> onReady) {
    std::lock_guard<std::mutex> lock(mu_);
    onReady_ = std::move(onReady);
}

void OpenDrtAsyncBaker::publishLocked(const OpenDrtSpec& spec, LutPtr lut) {
    published_ = std::move(lut);
    publishedSpec_ = spec;
    ++stats_.bakesPublished;
}

OpenDrtAsyncBaker::Frame OpenDrtAsyncBaker::acquire(const OpenDrtSpec& spec) {
    std::unique_lock<std::mutex> lock(mu_);
    ++stats_.acquires;
    if (published_ && sameSpec(publishedSpec_, spec)) {
        if (havePending_) {
            // Back on the published spec while a newer one was wanted: the newer bake is moot.
            havePending_ = false;
            if (inFlightCancel_) inFlightCancel_->store(true);
        }
        return {published_, false};
    }
    // Cheap path: the core's spec cache already holds the table (undo, drag back to a recent value).
    if (LutPtr hit = backend_.peek ? backend_.peek(spec) : nullptr) {
        havePending_ = false;
        if (inFlightCancel_) inFlightCancel_->store(true);
        publishLocked(spec, hit);
        return {hit, false};
    }
    if (!published_) {
        // FIRST frame: nothing to keep showing, so bake synchronously and the first picture is correct.
        havePending_ = false;
        if (inFlightCancel_) inFlightCancel_->store(true);
        lock.unlock();
        const std::atomic<bool> never{false};
        LutPtr lut = backend_.bake(spec, never);
        lock.lock();
        if (lut) {
            ++stats_.bakesStarted;
            publishLocked(spec, lut);
        }
        return {lut ? lut : published_, false};
    }
    ++stats_.staleAcquires;
    if (havePending_ && sameSpec(wanted_, spec)) return {published_, true}; // already wanted
    wanted_ = spec;
    havePending_ = true;
    if (inFlightCancel_) inFlightCancel_->store(true); // latest wins: stop the superseded bake
    cv_.notify_all();
    return {published_, true};
}

void OpenDrtAsyncBaker::run() {
    std::unique_lock<std::mutex> lock(mu_);
    for (;;) {
        cv_.wait(lock, [&] { return stop_ || (havePending_ && !inFlight_); });
        if (stop_) break;
        const OpenDrtSpec spec = wanted_;
        auto cancel = std::make_shared<std::atomic<bool>>(false);
        inFlight_ = true;
        inFlightCancel_ = cancel;
        ++stats_.bakesStarted;
        lock.unlock();
        LutPtr lut = backend_.bake(spec, *cancel);
        lock.lock();
        inFlight_ = false;
        inFlightCancel_.reset();
        const bool current = havePending_ && sameSpec(wanted_, spec) && !cancel->load();
        if (lut && current && !stop_) {
            havePending_ = false;
            publishLocked(spec, std::move(lut));
            std::function<void()> cb = onReady_;
            lock.unlock();
            if (cb) cb(); // outside the lock, once per published table
            lock.lock();
        } else if (lut) {
            ++stats_.bakesDropped; // finished, but superseded: never published
        } else {
            ++stats_.bakesCancelled;
        }
        // havePending_ stays as the latest request left it: a newer spec loops, a moot one idles.
        idleCv_.notify_all();
    }
    idleCv_.notify_all();
}

void OpenDrtAsyncBaker::waitIdle() {
    std::unique_lock<std::mutex> lock(mu_);
    idleCv_.wait(lock, [&] { return stop_ || (!havePending_ && !inFlight_); });
}

OpenDrtAsyncBaker::Stats OpenDrtAsyncBaker::stats() const {
    std::lock_guard<std::mutex> lock(mu_);
    return stats_;
}

} // namespace rawedit
