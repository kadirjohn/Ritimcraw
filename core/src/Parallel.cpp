#include <rawedit/Parallel.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>

namespace rawedit {

namespace {

// Leaky singleton: the pool is created on first use and NEVER destroyed.
// Static-duration threads would otherwise join (or fail to join) during
// global teardown while other threads are mid-shutdown — the classic
// exit-time race. Leaking is deliberate and bounded: one pool, at most
// kMaxWorkers threads, one set of primitives, per process.
struct RowPool {
    static constexpr int kMaxWorkers = 4;

    std::mutex mu;
    std::condition_variable cvWork;
    std::condition_variable cvDone;

    // The current job, guarded by mu. fn points at the caller's reference
    // and is only dereferenced for chunks of the job's generation; the
    // job's caller waits for running == 0 (all handed-out chunks done)
    // before returning, so the pointer never dangles. fn != nullptr also
    // marks the pool busy: a second thread calling in takes the serial
    // path instead of waiting on the bounded workers.
    const std::function<void(size_t, size_t)>* fn = nullptr;
    size_t rows = 0;       // total rows of the current job
    int nextChunk = 0;     // next chunk index to hand out
    int totalChunks = 0;   // chunks of the current job
    size_t chunkRows = 0;  // rows per chunk (the last chunk may be shorter)
    int running = 0;       // threads (caller or worker) currently inside fn
    unsigned generation = 0; // bumped once per job; workers park on it

    std::vector<std::thread> workers; // empty => every call runs serial

    explicit RowPool(int nWorkers) {
        for (int i = 0; i < nWorkers; ++i) {
            try {
                workers.emplace_back([this] { workerLoop(); });
            } catch (...) {
                // Thread construction failed (resource exhaustion). Keep
                // whichever workers exist; with fewer of them the chunks
                // simply spread thinner, and with none every call runs on
                // the caller. Never a failure mode.
                break;
            }
        }
    }

    void workerLoop() {
        std::unique_lock<std::mutex> lk(mu);
        unsigned seenGen = 0;
        for (;;) {
            // Park until a NEW job arrives (generation bump). Parking on
            // the generation — not on fn — means a worker that exhausts
            // its chunks sleeps instead of spinning while the job's
            // caller finishes and clears fn.
            cvWork.wait(lk, [this, &seenGen] { return generation != seenGen; });
            seenGen = generation;
            for (;;) {
                const int chunk = nextChunk++;
                if (chunk >= totalChunks)
                    break;
                const size_t begin = static_cast<size_t>(chunk) * chunkRows;
                if (begin >= rows)
                    break; // trailing empty chunk (ceil rounding); nothing left
                ++running;
                const size_t end = std::min(begin + chunkRows, rows);
                lk.unlock();
                (*fn)(begin, end);
                lk.lock();
                if (--running == 0)
                    cvDone.notify_one();
            }
        }
    }
};

std::atomic<bool> gForcedSerial{false};

RowPool* thePool() {
    static RowPool* pool = [] {
        const unsigned hw = std::thread::hardware_concurrency();
        if (hw <= 1)
            return new RowPool(0); // one (or unknown) hw thread: serial
        const int n = static_cast<int>(std::min<unsigned>(hw, RowPool::kMaxWorkers));
        return new RowPool(n);
    }();
    return pool;
}

} // namespace

void parallelForRows(
    size_t nRows,
    const std::function<void(size_t, size_t)>& fn) {
    if (nRows == 0 || !fn)
        return;

    if (gForcedSerial.load())
        return fn(0, nRows);

    RowPool* pool = thePool();
    const int workers = static_cast<int>(pool->workers.size());
    if (workers == 0)
        return fn(0, nRows);

    // One chunk per participant (workers + the caller), at least 2 rows
    // per chunk so a tiny image is not worth dispatching. chunks == 1
    // means the whole range is one chunk: run it on the caller without
    // ever touching the pool mutex.
    const int participants = workers + 1;
    const int n = static_cast<int>(nRows);
    const int chunks = std::max(1, std::min(participants, n / 2));
    if (chunks <= 1)
        return fn(0, nRows);
    const size_t chunkRows = (nRows + static_cast<size_t>(chunks) - 1)
        / static_cast<size_t>(chunks);

    std::unique_lock<std::mutex> lk(pool->mu);
    if (pool->fn != nullptr) {
        // The pool is busy (another develop thread holds it, or this is
        // a nested call from inside a worker). Run serial on the caller
        // — same bytes, no deadlock, never a failure mode.
        lk.unlock();
        return fn(0, nRows);
    }

    pool->fn = &fn;
    pool->rows = nRows;
    pool->totalChunks = chunks;
    pool->chunkRows = chunkRows;
    pool->nextChunk = 0;
    pool->generation += 1;
    pool->cvWork.notify_all();

    // The caller participates: it holds the lock from setup through the
    // first pull, so it always runs chunk 0; then it keeps pulling from
    // the same counter as the workers (chunk stealing — every participant
    // stays busy to the end of the range).
    for (;;) {
        const int chunk = pool->nextChunk++;
        if (chunk >= pool->totalChunks)
            break;
        const size_t begin = static_cast<size_t>(chunk) * pool->chunkRows;
        if (begin >= pool->rows)
            break; // trailing empty chunk (ceil rounding); nothing left
        ++pool->running;
        const size_t end = std::min(begin + pool->chunkRows, pool->rows);
        lk.unlock();
        fn(begin, end);
        lk.lock();
        if (--pool->running == 0)
            pool->cvDone.notify_one();
    }

    // Wait until every handed-out chunk has finished. Hand-out and the
    // running++ happen under the lock, so running == 0 with the lock held
    // means no thread is inside fn and none is about to pull a chunk.
    pool->cvDone.wait(lk, [&] { return pool->running == 0; });
    pool->fn = nullptr;
}

bool setParallelForcedSerial(bool forced) {
    return gForcedSerial.exchange(forced);
}

int parallelWorkerCount() {
    if (gForcedSerial.load())
        return 0;
    return static_cast<int>(thePool()->workers.size());
}

} // namespace rawedit
