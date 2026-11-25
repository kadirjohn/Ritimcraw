// OpenDrtAsyncBaker (F1-8e): latest-wins, no stale publish, cancellation, determinism, cache interaction.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/OpenDrtAsync.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <map>
#include <mutex>
#include <thread>
#include <vector>

using namespace rawedit;

namespace {

OpenDrtSpec specWithGrey(float grey) {
    OpenDrtSpec s;
    s.grey = grey;
    return s;
}

// A scripted backend: every bake blocks until the test releases it (or its cancel flag turns), so the
// tests control exactly when a "bake" finishes. The table's n field carries the grey value for identity.
struct FakeBackend {
    std::mutex mu;
    std::condition_variable cv;
    std::vector<float> started;      // grey of each bake, in start order
    std::vector<float> cancelled;    // grey of each bake that observed its cancel flag
    std::map<float, bool> released;  // grey -> may finish
    std::map<float, OpenDrtAsyncBaker::LutPtr> cache;
    bool autoRelease = false;

    OpenDrtAsyncBaker::Backend backend() {
        OpenDrtAsyncBaker::Backend b;
        b.bake = [this](const OpenDrtSpec& s, const std::atomic<bool>& cancel) -> OpenDrtAsyncBaker::LutPtr {
            std::unique_lock<std::mutex> lock(mu);
            started.push_back(s.grey);
            cv.notify_all();
            while (!autoRelease && !released[s.grey] && !cancel.load()) {
                cv.wait_for(lock, std::chrono::milliseconds(2));
            }
            if (cancel.load()) {
                cancelled.push_back(s.grey);
                return nullptr;
            }
            auto lut = std::make_shared<OpenDrtLut>();
            lut->n = static_cast<int>(s.grey * 10);
            OpenDrtAsyncBaker::LutPtr p = lut;
            cache[s.grey] = p;
            return p;
        };
        b.peek = [this](const OpenDrtSpec& s) -> OpenDrtAsyncBaker::LutPtr {
            std::lock_guard<std::mutex> lock(mu);
            auto it = cache.find(s.grey);
            return it == cache.end() ? nullptr : it->second;
        };
        return b;
    }
    void release(float grey) {
        std::lock_guard<std::mutex> lock(mu);
        released[grey] = true;
        cv.notify_all();
    }
    bool waitStarted(size_t count) {
        std::unique_lock<std::mutex> lock(mu);
        return cv.wait_for(lock, std::chrono::seconds(5), [&] { return started.size() >= count; });
    }
};

struct ReadyCounter {
    std::atomic<int> n{0};
    std::function<void()> fn() {
        return [this] { ++n; };
    }
};

} // namespace

TEST_CASE("first acquire bakes synchronously and is never stale") {
    FakeBackend fake;
    fake.autoRelease = true;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    auto f = baker.acquire(specWithGrey(1.0f));
    REQUIRE(f.lut);
    CHECK(f.lut->n == 10);
    CHECK_FALSE(f.stale);
    baker.waitIdle();
    CHECK(ready.n.load() == 0); // the synchronous bake needs no redraw
}

TEST_CASE("unchanged spec: no new bake, no redraw, never stale") {
    FakeBackend fake;
    fake.autoRelease = true;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    auto a = baker.acquire(specWithGrey(1.0f));
    for (int i = 0; i < 50; ++i) {
        auto f = baker.acquire(specWithGrey(1.0f));
        CHECK(f.lut == a.lut);
        CHECK_FALSE(f.stale);
    }
    baker.waitIdle();
    CHECK(fake.started.size() == 1);
    CHECK(ready.n.load() == 0);
}

TEST_CASE("changed spec: old table keeps showing, new one swaps in with exactly one ready") {
    FakeBackend fake;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    fake.release(1.0f);
    auto first = baker.acquire(specWithGrey(1.0f));
    auto f = baker.acquire(specWithGrey(2.0f));
    CHECK(f.stale);
    CHECK(f.lut == first.lut); // old LUT keeps being shown
    REQUIRE(fake.waitStarted(2));
    auto again = baker.acquire(specWithGrey(2.0f)); // repeated frames while baking: still stale, no new bake
    CHECK(again.stale);
    CHECK(again.lut == first.lut);
    fake.release(2.0f);
    baker.waitIdle();
    CHECK(ready.n.load() == 1);
    auto done = baker.acquire(specWithGrey(2.0f));
    CHECK_FALSE(done.stale);
    CHECK(done.lut->n == 20);
    CHECK(fake.started.size() == 2);
}

TEST_CASE("latest wins: a drag never queues a backlog and superseded bakes never publish") {
    FakeBackend fake;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    fake.release(1.0f);
    auto first = baker.acquire(specWithGrey(1.0f));
    baker.acquire(specWithGrey(2.0f));
    REQUIRE(fake.waitStarted(2));
    // The drag moves on while bake 2.0 is in flight: 3.0, 4.0, 5.0 arrive back to back.
    baker.acquire(specWithGrey(3.0f));
    baker.acquire(specWithGrey(4.0f));
    baker.acquire(specWithGrey(5.0f));
    REQUIRE(fake.waitStarted(3));
    // 2.0 was cancelled mid-flight; only the latest (5.0) is ever baked next.
    CHECK(fake.started[2] == 5.0f);
    fake.release(5.0f);
    baker.waitIdle();
    CHECK(fake.started.size() == 3); // 1.0, 2.0, 5.0: 3.0 and 4.0 never started (no backlog)
    CHECK(fake.cancelled.size() == 1);
    CHECK(fake.cancelled[0] == 2.0f);
    CHECK(ready.n.load() == 1);
    auto f = baker.acquire(specWithGrey(5.0f));
    CHECK_FALSE(f.stale);
    CHECK(f.lut->n == 50);
    auto st = baker.stats();
    CHECK(st.bakesPublished == 2); // 1.0 (sync) and 5.0
    (void)first;
}

TEST_CASE("a bake that finishes after being superseded is dropped, not published") {
    FakeBackend fake;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    fake.release(1.0f);
    auto first = baker.acquire(specWithGrey(1.0f));
    fake.release(2.0f); // 2.0 will complete instantly, racing the supersede below
    // Hold the baker's thread by superseding immediately after the request.
    baker.acquire(specWithGrey(2.0f));
    baker.acquire(specWithGrey(3.0f));
    fake.release(3.0f);
    baker.waitIdle();
    // Whatever the interleaving, the published table is the LATEST spec's and 2.0 never became current
    // after 3.0 did.
    auto f = baker.acquire(specWithGrey(3.0f));
    CHECK_FALSE(f.stale);
    CHECK(f.lut->n == 30);
    CHECK(ready.n.load() >= 1);
    CHECK(ready.n.load() <= 2);
    (void)first;
}

TEST_CASE("returning to the published spec cancels the pending bake") {
    FakeBackend fake;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    fake.release(1.0f);
    auto first = baker.acquire(specWithGrey(1.0f));
    baker.acquire(specWithGrey(2.0f));
    REQUIRE(fake.waitStarted(2));
    auto back = baker.acquire(specWithGrey(1.0f));
    CHECK_FALSE(back.stale);
    CHECK(back.lut == first.lut);
    baker.waitIdle();
    CHECK(ready.n.load() == 0);
    CHECK(fake.cancelled.size() == 1);
}

TEST_CASE("spec-cache hit publishes synchronously without a redraw") {
    FakeBackend fake;
    fake.autoRelease = true;
    ReadyCounter ready;
    OpenDrtAsyncBaker baker(fake.backend(), ready.fn());
    baker.acquire(specWithGrey(1.0f));
    baker.acquire(specWithGrey(2.0f));
    baker.waitIdle();
    CHECK(ready.n.load() == 1);
    auto f = baker.acquire(specWithGrey(1.0f)); // 1.0 is in the cache: undo is instant
    CHECK_FALSE(f.stale);
    CHECK(f.lut->n == 10);
    CHECK(ready.n.load() == 1);
}

TEST_CASE("destructor cancels an in-flight bake and joins") {
    FakeBackend fake;
    {
        OpenDrtAsyncBaker baker(fake.backend(), {});
        fake.release(1.0f);
        baker.acquire(specWithGrey(1.0f));
        baker.acquire(specWithGrey(2.0f));
        REQUIRE(fake.waitStarted(2));
    } // joins; must not hang
    CHECK(fake.cancelled.size() == 1);
}

TEST_CASE("real bake: async result is bit-identical to the synchronous bake (determinism)") {
    OpenDrtSpec a;
    OpenDrtSpec b;
    b.look = 1;
    b.grey = 12.0f;
    OpenDrtAsyncBaker baker({});
    auto fa = baker.acquire(a);
    REQUIRE(fa.lut);
    CHECK_FALSE(fa.stale);
    auto fb = baker.acquire(b);
    CHECK(fb.stale);
    CHECK(fb.lut == fa.lut);
    baker.waitIdle();
    auto done = baker.acquire(b);
    CHECK_FALSE(done.stale);
    const OpenDrtLut ref = bakeOpenDrtLut(b);
    REQUIRE(done.lut->n == ref.n);
    CHECK(done.lut->rgb == ref.rgb);
    // The same table object comes out of the core's spec cache (export's synchronous door).
    CHECK(bakedOpenDrtLut(b).get() == done.lut.get());
}

TEST_CASE("bake cancel flag: cancelled bake returns an empty table and is not cached") {
    OpenDrtSpec s;
    s.grey = 7.0f;
    std::atomic<bool> cancel{true};
    CHECK(bakedOpenDrtLutCancellable(s, cancel) == nullptr);
    CHECK(peekBakedOpenDrtLut(s) == nullptr);
    CHECK(bakeOpenDrtLut(s, kOpenDrtLutSize, &cancel).n == 0);
    const OpenDrtLut plain = bakeOpenDrtLut(s, 9);
    std::atomic<bool> go{false};
    CHECK(bakeOpenDrtLut(s, 9, &go).rgb == plain.rgb);
}
