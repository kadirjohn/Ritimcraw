// Asynchronous OpenDRT bake manager (F1-8e). The interactive preview must not stall frames while the
// user drags an OpenDRT slider: the bake runs on ONE background thread, the previous table keeps being
// shown until the new one is ready, and only the LATEST requested spec matters.
//
// Contract
//  - acquire(spec) never blocks on a bake except the FIRST call (no table published yet), which bakes
//    synchronously so the first picture is correct.
//  - latest-wins: a request for a new spec cancels the in-flight bake (polled per row) and replaces any
//    pending one; there is never a backlog. A result whose spec was superseded is dropped, never published.
//  - tables are immutable shared_ptr<const OpenDrtLut>; publishing swaps the pointer under a mutex.
//  - onReady fires on the bake thread after a publish, outside the manager's lock, exactly once per
//    table published by the worker. It must only post work (e.g. ask for ONE redraw); it must not call
//    back into acquire.
//  - Export does not use this class: it calls bakedOpenDrtLut() directly (synchronous, never stale).
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>

#include <rawedit/OpenDrt.h>

namespace rawedit {

class OpenDrtAsyncBaker {
public:
    using LutPtr = std::shared_ptr<const OpenDrtLut>;
    struct Backend {
        // Returns the table for [spec], or null when [cancel] turned true. Default: the core's cached bake.
        std::function<LutPtr(const OpenDrtSpec&, const std::atomic<bool>& cancel)> bake;
        // Cache lookup only (null on a miss). Default: peekBakedOpenDrtLut.
        std::function<LutPtr(const OpenDrtSpec&)> peek;
    };
    struct Frame {
        LutPtr lut;         // the table to draw with (never null after acquire)
        bool stale = false; // true when [lut] is an older spec's table and a newer one is being baked
    };
    struct Stats {
        uint64_t bakesStarted = 0, bakesPublished = 0, bakesCancelled = 0, bakesDropped = 0;
        uint64_t staleAcquires = 0, acquires = 0;
    };

    explicit OpenDrtAsyncBaker(std::function<void()> onReady = {});
    OpenDrtAsyncBaker(Backend backend, std::function<void()> onReady);
    ~OpenDrtAsyncBaker();
    OpenDrtAsyncBaker(const OpenDrtAsyncBaker&) = delete;
    OpenDrtAsyncBaker& operator=(const OpenDrtAsyncBaker&) = delete;

    // The preview's per-frame door (any thread, in practice the GL thread).
    Frame acquire(const OpenDrtSpec& spec);

    // Replaces the ready callback (thread-safe).
    void setOnReady(std::function<void()> onReady);

    // Blocks until no bake is in flight or pending (tests; also handy before teardown).
    void waitIdle();

    Stats stats() const;

private:
    void run();
    void publishLocked(const OpenDrtSpec& spec, LutPtr lut);

    Backend backend_;
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::condition_variable idleCv_;
    std::thread thread_;
    bool stop_ = false;

    LutPtr published_;
    OpenDrtSpec publishedSpec_;
    bool havePending_ = false; // the latest wanted spec is not published yet
    OpenDrtSpec wanted_;
    bool inFlight_ = false;
    std::shared_ptr<std::atomic<bool>> inFlightCancel_;
    Stats stats_;
    std::function<void()> onReady_;
};

} // namespace rawedit
