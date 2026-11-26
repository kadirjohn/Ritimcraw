// hr_equivalence — night A6 (2026-11-26): the byte-equality oracle for the recovery CPU reference.
//
// recoverClippedHighlights is reworked for speed under the night rule "output BIT-IDENTICAL".
// Relation-based tests (test_highlight_recovery) cannot see a 1-ulp drift; this tool can.
//
//   hr_equivalence record <dir>    run the fixture set; write output blobs + a manifest of hashes
//   hr_equivalence verify <dir>    rerun; compare every output byte-for-byte against the manifest
//   hr_equivalence bench [w h r]  chrono recoverClippedHighlights on synthetic frames (median of r)
//   hr_equivalence benchdev <clip.mcraw> <frame> [reps]  developCpu recovery-on vs -off delta, real frame
//
// Fixtures are SYNTHETIC and deterministic (fixed seeds) so the oracle never depends on files:
//  - "g*" run the M1 scene family through developCpu (the whole shipped pipeline incl.
//    recoveryClipLevels and the shipped K=4 headroom), 256x192..4096x3072;
//  - "d*" call recoverClippedHighlights directly on hand-built img/clipMask (fractional mask
//    values incl. exact 0.5 boundaries, discs large enough to force the 128/512-block scales,
//    all-clipped and no-clip controls, randomized seeds incl. odd sizes, values near kEps),
//    each under 3 param variants: p0 = defaults, p1 = shipped-lookalike (K=4), p2 = K=2.5 +
//    minRefFrac=0.3 with stats ON. Stats tuples are hashed with the image.
// With HR_EQ_REALCLIP=<clip.mcraw> set, one real-frame developCpu case ("real") is added;
// unset, it is skipped on both record and verify (the synthetic set stays the permanent oracle).
// Exit 0 = every fixture byte-equal.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

struct Rng {
    uint32_t s;
    uint32_t next() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; }
    float f() { return float(next()) / 4294967295.0f; }
};

uint64_t fnv1a(const void* data, size_t n) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 0x100000001b3ULL; }
    return h;
}

constexpr uint32_t kCfaWhite = 4095, kCfaBlack = 64;
constexpr float kNeutral[3] = {0.55f, 1.0f, 0.70f};
constexpr float kEps = 1e-4f;

int siteColor(uint32_t x, uint32_t y) {  // RGGB
    return ((y & 1) == 0) ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
}

// ---------- g* fixtures: M1 scene family through developCpu ----------
struct SceneKnobs {
    uint32_t w, h;
    float drift = 0.08f;
    float dim = 1.0f;      // <1 keeps everything below the clip threshold
    float disc = 0.0f;     // brightness bumps -> clipped cores of several sizes
    uint32_t seed = 0;     // >0 = per-pixel raw noise
};

void sceneRgb(const SceneKnobs& k, uint32_t x, uint32_t y, float out[3]) {
    const float u = float(x) / float(k.w - 1), v = float(y) / float(k.h - 1);
    float lum = 0.3f + 2.7f * u + k.drift * (1.0f - u) * v;
    if (k.disc > 0.0f) {
        const float fx[3] = {0.3f, 0.62f, 0.8f}, fy[3] = {0.4f, 0.7f, 0.2f}, fr[3] = {0.015f, 0.05f, 0.11f};
        for (int d = 0; d < 3; ++d) {
            const float dx = u - fx[d], dy = v - fy[d];
            lum += k.disc * std::exp(-(dx * dx + dy * dy) / (2.0f * fr[d] * fr[d]));
        }
    }
    const float warm = 1.0f + k.drift * (1.0f - u) * (1.0f - v) - 0.8f * k.drift * u * v;
    out[0] = lum * warm * k.dim;
    out[1] = lum * k.dim;
    out[2] = lum / warm * k.dim;
}

ImageF developScene(const SceneKnobs& k) {
    FrameInfo info;
    info.width = k.w; info.height = k.h; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(k.w), int(k.h)};
    RawMeta meta;
    for (int i = 0; i < 4; ++i) meta.black[i] = uint16_t(kCfaBlack);
    meta.white = uint16_t(kCfaWhite);
    for (int c = 0; c < 3; ++c) { meta.asShotNeutral[c] = kNeutral[c]; meta.wbGains[c] = 1.0f / kNeutral[c]; }
    meta.colorMatrix[0] = meta.colorMatrix[4] = meta.colorMatrix[8] = 1;
    meta.forwardMatrix[0] = meta.forwardMatrix[4] = meta.forwardMatrix[8] = 1;
    Rng rng{k.seed * 2654435761u + 1u};
    std::vector<uint16_t> cfa(size_t(k.w) * k.h);
    for (uint32_t y = 0; y < k.h; ++y)
        for (uint32_t x = 0; x < k.w; ++x) {
            float s[3];
            sceneRgb(k, x, y, s);
            const int c = siteColor(x, y);
            float norm = s[c] * kNeutral[c];
            if (k.seed) norm += (rng.f() - 0.5f) * 0.02f;
            norm = std::min(norm, 1.0f);
            cfa[size_t(y) * k.w + x] = uint16_t(kCfaBlack + norm * (kCfaWhite - kCfaBlack) + 0.5f);
        }
    Grade g;
    g.highlightReconstruction = true;
    g.highlightRecovery = true;
    return developCpu(cfa.data(), info, meta, g);
}

// ---------- d* fixtures: direct recoverClippedHighlights ----------
// patterns: 0 ramp+three discs+smooth mask, 1 random blobs, 2 fully random,
//           3 checkerboard+border strip, 4 all clipped, 5 no clip
void buildDirect(int pattern, uint32_t w, uint32_t h, uint32_t seed, ImageF& img, ImageF& mask) {
    img.w = mask.w = w; img.h = mask.h = h;
    const size_t n = size_t(w) * h;
    img.rgb.assign(n * 3, 0.0f);
    mask.rgb.assign(n * 3, 0.0f);
    Rng rng{seed * 40503u + 7u};

    struct Blob { float cx, cy, rad, amp; bool cr, cg, cb; int seedMs; };
    std::vector<Blob> blobs;
    if (pattern == 0) {
        blobs.push_back({0.30f, 0.40f, 0.025f, 3.0f, true,  true,  false, 1});
        blobs.push_back({0.72f, 0.65f, 0.050f, 2.5f, false, true,  true,  2});
        blobs.push_back({0.85f, 0.18f, 0.090f, 2.0f, true,  false, true,  3});
    } else if (pattern == 1) {
        Rng br{seed * 7u + 99u};
        for (int b = 0; b < 12; ++b) {
            const float u3 = br.f(), u4 = br.f();
            blobs.push_back({br.f(), br.f(), 0.02f + 0.10f * br.f(), 1.5f + 2.5f * u3 * u4,
                             u3 > 0.33f, br.f() > 0.25f, u4 > 0.33f, 4});
        }
    }

    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float u = w > 1 ? float(x) / float(w - 1) : 0.0f;
            const float v = h > 1 ? float(y) / float(h - 1) : 0.0f;
            const size_t i = (size_t(y) * w + x) * 3;
            float s[3] = {0.3f, 0.3f, 0.3f};
            if (pattern == 0 || pattern == 1) {
                const float lum = 0.25f + 0.95f * u + 0.10f * v;
                const float warm = 1.0f + 0.12f * (1.0f - v);
                s[0] = lum * warm; s[1] = lum; s[2] = lum / warm;
                for (const Blob& b : blobs) {
                    const float dx = u - b.cx, dy = v - b.cy;
                    const float amp = b.amp * std::exp(-(dx * dx + dy * dy) / (2.0f * b.rad * b.rad));
                    s[0] += amp * (b.cr ? 1.0f : 0.4f);
                    s[1] += amp * (b.cg ? 1.0f : 0.4f);
                    s[2] += amp * (b.cb ? 1.0f : 0.4f);
                }
            } else if (pattern == 2) {
                for (int c = 0; c < 3; ++c) s[c] = -0.02f + 1.7f * rng.f();
            } else if (pattern == 3) {
                const float lum = 0.25f + 0.95f * u;
                s[0] = lum * 1.6f; s[1] = lum * 1.6f; s[2] = lum;
            } else if (pattern == 4) {
                for (int c = 0; c < 3; ++c) s[c] = 0.9f + 0.4f * rng.f();
            } else if (pattern == 5) {
                for (int c = 0; c < 3; ++c) s[c] = 0.10f + 0.30f * u;
            }
            // tiny noise everywhere, an exact kEps band, exact kEps+
            for (int c = 0; c < 3; ++c) s[c] += (rng.f() - 0.5f) * 0.03f;
            if ((pattern == 0 && y == h / 7) || (pattern == 1 && (y % 37) == 0)) {
                s[1] = kEps;
                if ((x & 3) == 0) s[0] = kEps * 1.000001f;
                if ((x & 3) == 1) s[2] = kEps * 0.999999f;
            }
            img.rgb[i] = s[0]; img.rgb[i + 1] = s[1]; img.rgb[i + 2] = s[2];

            float m[3] = {0.0f, 0.0f, 0.0f};
            if (pattern == 2) {
                const float q[5] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
                for (int c = 0; c < 3; ++c) m[c] = q[rng.next() % 5];
            } else if (pattern == 3) {  // per-channel checkers: 1-2 channel pixels + exact 0.5
                const uint32_t ckb = ((x / 8) + (y / 8)) & 1;
                const uint32_t ck2 = ((x / 16) + (y / 16)) & 1;
                const bool border = x < 3 || y < 3 || x + 3 >= w || y + 3 >= h;
                m[0] = border ? 1.0f : (ckb ? 1.0f : 0.5f);           // R at the >= 0.5 boundary
                m[1] = (border || ck2 == 0) ? 1.0f : 0.0f;            // G coarse checker
                m[2] = 0.0f;                                          // B unclipped ...
                if (y == h / 3 || x == w / 2) m[2] = 0.5f;            // ... except the boundary band
            } else if (pattern == 4) {
                for (int c = 0; c < 3; ++c) m[c] = 1.0f;
            } else if (pattern == 0 || pattern == 1) {
                for (int c = 0; c < 3; ++c) {
                    const float val = s[c];
                    m[c] = val >= 1.05f ? 1.0f : val >= 0.98f ? 0.75f
                         : val >= 0.94f ? 0.5f : val >= 0.88f ? 0.25f : 0.0f;
                }
                for (const Blob& b : blobs) {
                    const float dx = u - b.cx, dy = v - b.cy;
                    const float d2 = dx * dx + dy * dy;
                    if (d2 < b.rad * b.rad) {
                        const float core = d2 < 0.25f * b.rad * b.rad ? 1.0f : 0.5f;
                        if (b.cr) m[0] = core;
                        if (b.cg) m[1] = core;
                        if (b.cb) m[2] = core;
                    }
                }
                if (pattern == 0 && y == h / 5) { m[0] = m[1] = m[2] = 0.5f; }  // exact-boundary row
            }
            mask.rgb[i] = m[0]; mask.rgb[i + 1] = m[1]; mask.rgb[i + 2] = m[2];
        }
}

// ---------- stats blob ----------
std::vector<uint8_t> statsToBlob(const RecoveryStats* st) {
    std::vector<uint8_t> b;
    if (st == nullptr) return b;
    auto put = [&b](const void* p, size_t n) {
        const uint8_t* q = static_cast<const uint8_t*>(p);
        b.insert(b.end(), q, q + n);
    };
    uint64_t n = st->ratio.size();
    put(&n, 8); put(st->ratio.data(), n * sizeof(float));
    put(&n, 8); put(st->refFrac.data(), st->refFrac.size() * sizeof(float));
    n = st->chan.size();
    put(&n, 8); put(st->chan.data(), n);
    n = st->pix.size();
    put(&n, 8); put(st->pix.data(), n * sizeof(uint32_t));
    return b;
}

struct FixtureOut {
    uint64_t imgHash = 0, statsHash = 0;
    std::vector<float> img;
    std::vector<uint8_t> stats;
};

struct Fixture {
    std::string name;
    std::function<FixtureOut()> run;
};

FixtureOut capture(const ImageF& img, const RecoveryStats* st) {
    FixtureOut o;
    o.img = img.rgb;
    o.imgHash = fnv1a(o.img.data(), o.img.size() * sizeof(float));
    o.stats = statsToBlob(st);
    o.statsHash = fnv1a(o.stats.data(), o.stats.size());
    return o;
}

// direct recoverClippedHighlights run (img untouched: works on a copy)
FixtureOut runDirect(const ImageF& img0, const ImageF& mask, const RecoveryParams& p, bool withStats) {
    ImageF img = img0;
    RecoveryStats st;
    recoverClippedHighlights(img, mask, p, withStats ? &st : nullptr);
    // a null-stats run must be indentical to a stats run on the image: compare image only for both
    if (!withStats) return capture(img, nullptr);
    return capture(img, &st);
}

std::vector<Fixture> buildFixtures() {
    std::vector<Fixture> fx;
    auto addG = [&](const char* name, SceneKnobs k) {
        fx.push_back({name, [k]() {
            ImageF img = developScene(k);
            FixtureOut o;
            o.img = std::move(img.rgb);
            o.imgHash = fnv1a(o.img.data(), o.img.size() * sizeof(float));
            return o;  // shipped params path: developCpu owns them
        }});
    };
    auto addD = [&](const char* name, int pattern, uint32_t w, uint32_t h, uint32_t seed) {
        ImageF img, mask;
        buildDirect(pattern, w, h, seed, img, mask);
        const RecoveryParams p0;                       // defaults: no caps
        RecoveryParams p1;                             // shipped-lookalike
        p1.headroom = 4.0f; p1.refLevel[0] = 1.02f; p1.refLevel[1] = 1.0f; p1.refLevel[2] = 0.83f;
        RecoveryParams p2;                             // both gates on
        p2.headroom = 2.5f; p2.minRefFrac = 0.3f;
        p2.refLevel[0] = 1.1f; p2.refLevel[1] = 0.98f; p2.refLevel[2] = 0.9f;
        const std::string base = name;
        fx.push_back({base + "-p0", [img, mask, p0]() { return runDirect(img, mask, p0, false); }});
        fx.push_back({base + "-p1", [img, mask, p1]() { return runDirect(img, mask, p1, true); }});
        fx.push_back({base + "-p2", [img, mask, p2]() { return runDirect(img, mask, p2, true); }});
    };

    addG("g1-ramp-256x192", SceneKnobs{256, 192});
    addG("g2-drift025-256x192", SceneKnobs{256, 192, 0.25f});
    addG("g3-noclip-256x192", SceneKnobs{256, 192, 0.08f, 0.28f});
    addG("g4-noise-odd-101x67", SceneKnobs{101, 67, 0.10f, 1.0f, 0.0f, 11u});
    addG("g5-discs-800x600", SceneKnobs{800, 600, 0.10f, 1.0f, 2.5f});
    addG("g6-discs-4096x3072", SceneKnobs{4096, 3072, 0.09f, 1.0f, 2.2f});

    addD("d01-ramp-64x48", 0, 64, 48, 1);
    addD("d02-ramp-101x67", 0, 101, 67, 2);
    addD("d03-ramp-256x192", 0, 256, 192, 3);
    addD("d04-blobs-129x83", 1, 129, 83, 4);
    addD("d05-random-101x67", 2, 101, 67, 5);
    addD("d06-blobs-300x201", 1, 300, 201, 6);
    addD("d07-ramp-640x481", 0, 640, 481, 7);
    addD("d08-ramp-1024x768", 0, 1024, 768, 8);      // discs -> 128-block scale
    addD("d09-checker-256x192", 3, 256, 192, 12);   // per-channel checkers, exact 0.5 boundaries
    addD("d11-allclip-64x48", 4, 64, 48, 13);
    addD("d12-noclip-33x17", 5, 33, 17, 14);
    // heavy: 4K direct with discs (512-block scale) — 2 variants only (baseline is slow)
    {
        ImageF img, mask;
        buildDirect(0, 4096, 3072, 21, img, mask);
        const RecoveryParams p0;
        RecoveryParams p1;
        p1.headroom = 4.0f; p1.refLevel[0] = 1.02f; p1.refLevel[1] = 1.0f; p1.refLevel[2] = 0.83f;
        fx.push_back({"d13-4k-discs-p0", [img, mask, p0]() { return runDirect(img, mask, p0, true); }});
        fx.push_back({"d13-4k-discs-p1", [img, mask, p1]() { return runDirect(img, mask, p1, false); }});
    }
    return fx;
}

bool writeAll(const char* dir, const std::vector<std::pair<std::string, FixtureOut>>& outs) {
    const std::string base = dir;
    FILE* mf = std::fopen((base + "/manifest.txt").c_str(), "w");
    if (!mf) { std::fprintf(stderr, "cannot write manifest in %s\n", dir); return false; }
    std::fprintf(mf, "# hr_equivalence manifest v1\n");
    for (const auto& kv : outs) {
        const std::string ip = base + "/" + kv.first + ".img.bin";
        const std::string sp = base + "/" + kv.first + ".stats.bin";
        std::ofstream f(ip, std::ios::binary); f.write(reinterpret_cast<const char*>(kv.second.img.data()),
            std::streamsize(kv.second.img.size() * sizeof(float)));
        std::ofstream g(sp, std::ios::binary); g.write(reinterpret_cast<const char*>(kv.second.stats.data()),
            std::streamsize(kv.second.stats.size()));
        if (!f || !g) { std::fprintf(stderr, "cannot write blob %s\n", kv.first.c_str()); std::fclose(mf); return false; }
        std::fprintf(mf, "%s %016llx %016llx %zu %zu\n", kv.first.c_str(),
                     (unsigned long long)kv.second.imgHash, (unsigned long long)kv.second.statsHash,
                     kv.second.img.size(), kv.second.stats.size());
    }
    std::fclose(mf);
    return true;
}

}  // namespace

int modeRecord(const char* dir) {
    std::vector<std::pair<std::string, FixtureOut>> outs;
    std::vector<Fixture> fx = buildFixtures();
    const char* realClip = std::getenv("HR_EQ_REALCLIP");
    if (realClip == nullptr) std::printf("HR_EQ_REALCLIP unset: real-frame fixture skipped\n");
    for (const Fixture& f : fx) {
        std::printf("record %-28s ...", f.name.c_str());
        std::fflush(stdout);
        outs.push_back({f.name, f.run()});
        std::printf(" img=%016llx stats=%016llx\n",
                    (unsigned long long)outs.back().second.imgHash,
                    (unsigned long long)outs.back().second.statsHash);
    }
    if (realClip != nullptr) {
        std::printf("record %-28s ...", "real-f0");
        std::fflush(stdout);
        auto opened = Source::open(std::string(realClip));
        if (!opened.isOk()) { std::fprintf(stderr, "\nREALCLIP open failed: %s\n", opened.detail.c_str()); return 1; }
        Source& src = **opened.value;
        auto dec = src.readFrame(0);
        if (!dec.isOk()) { std::fprintf(stderr, "\nREALCLIP decode failed\n"); return 1; }
        const FrameInfo info = src.frameInfo(0);
        const RawMeta meta = src.rawMeta();
        Grade g;
        g.highlightReconstruction = true;
        g.highlightRecovery = true;
        const ImageF img = developCpu(dec.value->data(), info, meta, g);
        FixtureOut o;
        o.img = std::move(img.rgb);
        o.imgHash = fnv1a(o.img.data(), o.img.size() * sizeof(float));
        outs.push_back({"real-f0", o});
        std::printf(" img=%016llx\n", (unsigned long long)o.imgHash);
    }
    if (!writeAll(dir, outs)) return 1;
    std::printf("RECORDED %zu fixtures in %s\n", outs.size(), dir);
    return 0;
}

int modeVerify(const char* dir) {
    const std::string base = dir;
    std::ifstream mf(base + "/manifest.txt");
    if (!mf) { std::fprintf(stderr, "cannot read manifest in %s\n", dir); return 1; }
    std::string line;
    std::getline(mf, line);  // header
    size_t pass = 0, fail = 0;
    std::vector<Fixture> fx = buildFixtures();
    size_t fi = 0;
    const char* realClip = std::getenv("HR_EQ_REALCLIP");
    if (realClip == nullptr) std::printf("HR_EQ_REALCLIP unset: real-frame fixture skipped\n");
    while (std::getline(mf, line)) {
        if (line.empty()) continue;
        char name[64];
        unsigned long long ih, sh;
        size_t il, sl;
        if (std::sscanf(line.c_str(), "%63s %llx %llx %zu %zu", name, &ih, &sh, &il, &sl) != 5) {
            std::fprintf(stderr, "bad manifest line: %s\n", line.c_str());
            return 1;
        }
        FixtureOut got;
        bool haveIt = false;
        if (fi < fx.size() && fx[fi].name == name) { got = fx[fi].run(); haveIt = true; ++fi; }
        else if (std::string(name) == "real-f0") {
            if (realClip == nullptr) { std::printf("SKIP %-28s (fixture needs HR_EQ_REALCLIP; unset)\n", name); continue; }
            auto opened = Source::open(std::string(realClip));
            if (!opened.isOk()) { std::fprintf(stderr, "REALCLIP open failed\n"); return 1; }
            Source& src = **opened.value;
            auto dec = src.readFrame(0);
            if (!dec.isOk()) { std::fprintf(stderr, "REALCLIP decode failed\n"); return 1; }
            const FrameInfo info = src.frameInfo(0);
            const RawMeta meta = src.rawMeta();
            Grade g;
            g.highlightReconstruction = true;
            g.highlightRecovery = true;
            const ImageF img = developCpu(dec.value->data(), info, meta, g);
            got.img = std::move(img.rgb);
            got.imgHash = fnv1a(got.img.data(), got.img.size() * sizeof(float));
            haveIt = true;
        }
        if (!haveIt) { std::fprintf(stderr, "fixture list mismatch at %s\n", name); return 1; }
        bool ok = got.imgHash == ih && got.statsHash == sh;
        if (ok) {  // full byte compare against the blob
            std::ifstream f(base + "/" + std::string(name) + ".img.bin", std::ios::binary);
            std::vector<char> want{std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
            const size_t n = got.img.size() * sizeof(float);
            ok = !got.img.empty() && want.size() == n &&
                 std::memcmp(want.data(), got.img.data(), n) == 0;
            if (ok && sl > 0) {
                std::ifstream g2(base + "/" + std::string(name) + ".stats.bin", std::ios::binary);
                std::vector<char> want2{std::istreambuf_iterator<char>(g2), std::istreambuf_iterator<char>()};
                ok = want2.size() == got.stats.size() &&
                     std::memcmp(want2.data(), got.stats.data(), got.stats.size()) == 0;
            }
        }
        if (ok) { ++pass; std::printf("PASS %-28s img=%016llx stats=%016llx\n", name, ih, sh); }
        else { ++fail; std::printf("FAIL %-28s got img=%016llx stats=%016llx want img=%016llx stats=%016llx\n",
                    name, (unsigned long long)got.imgHash, (unsigned long long)got.statsHash, ih, sh); }
    }
    if (fi != fx.size()) { std::fprintf(stderr, "manifest has fewer entries than fixture list\n"); return 1; }
    std::printf("VERIFY %zu/%zu fixtures byte-equal, %zu failed\n", pass, pass + fail, fail);
    return fail == 0 ? 0 : 1;
}

namespace {

double medianOf(std::vector<double>& v) {
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

}  // namespace

int modeBench(uint32_t w, uint32_t h, int reps) {
    struct Case { const char* name; int pattern; uint32_t seed; };
    const Case cases[] = {
        {"heavy-discs", 0, 21},
        {"blob-clusters", 1, 31},
        {"no-clip-control", 5, 41},
    };
    std::printf("BENCH recoverClippedHighlights %ux%u reps=%d (Apple desktop, thread pool as shipped)\n", w, h, reps);
    for (const Case& cs : cases) {
        ImageF img0, mask;
        buildDirect(cs.pattern, w, h, cs.seed, img0, mask);
        // workload description: clipped fraction + the (c,r) pairs it needs (mirror of the real rule)
        {
            size_t clipped = 0;
            bool need[3][3] = {};
            for (size_t i = 0; i < size_t(w) * h; ++i) {
                const uint8_t cf[3] = {uint8_t(mask.rgb[i * 3] >= 0.5f ? 1 : 0),
                                       uint8_t(mask.rgb[i * 3 + 1] >= 0.5f ? 1 : 0),
                                       uint8_t(mask.rgb[i * 3 + 2] >= 0.5f ? 1 : 0)};
                const int nc = cf[0] + cf[1] + cf[2];
                if (nc == 0) continue;
                ++clipped;
                if (nc == 3) continue;
                int r = !cf[1] ? 1
                        : (img0.rgb[i * 3] >= img0.rgb[i * 3 + 2]) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);
                for (int c = 0; c < 3; ++c)
                    if (cf[c]) need[c][r] = true;
            }
            int pairs = 0;
            for (int c = 0; c < 3; ++c)
                for (int r = 0; r < 3; ++r) pairs += need[c][r];
            std::printf("  %-16s clipped=%0.2f%% pair-matrix-entries=%d\n", cs.name,
                        100.0 * double(clipped) / double(size_t(w) * h), pairs);
        }
        std::vector<double> ms;
        for (int i = 0; i <= reps; ++i) {  // rep 0 = warm-up
            ImageF img = img0;
            const RecoveryParams p;
            const auto t0 = std::chrono::steady_clock::now();
            recoverClippedHighlights(img, mask, p, nullptr);
            const auto t1 = std::chrono::steady_clock::now();
            if (i > 0) ms.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());
        }
        std::printf("  %-16s median=%.2fms min=%.2f max=%.2f (runs:", cs.name, medianOf(ms),
                    *std::min_element(ms.begin(), ms.end()), *std::max_element(ms.begin(), ms.end()));
        for (double m : ms) std::printf(" %.2f", m);
        std::printf(")\n");
    }
    return 0;
}

int modeBenchDev(const char* clip, size_t frame, int reps) {
    auto opened = Source::open(std::string(clip));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    if (frame >= src.frameCount()) { std::fprintf(stderr, "frame out of range\n"); return 1; }
    auto dec = src.readFrame(frame);
    if (!dec.isOk()) { std::fprintf(stderr, "decode: %s\n", dec.detail.c_str()); return 1; }
    const std::vector<uint16_t> cfa = std::move(*dec.value);
    const FrameInfo info = src.frameInfo(frame);
    const RawMeta meta = src.rawMeta();
    std::printf("BENCH-DEV %s frame=%zu %ux%u reps=%d developCpu recovery-on minus off\n",
                clip, frame, info.width, info.height, reps);
    std::vector<double> on, off;
    for (int i = 0; i <= reps; ++i) {
        Grade g;
        g.highlightReconstruction = true;
        g.highlightRecovery = false;
        const auto t0 = std::chrono::steady_clock::now();
        developCpu(cfa.data(), info, meta, g);
        const auto t1 = std::chrono::steady_clock::now();
        g.highlightRecovery = true;
        const auto t2 = std::chrono::steady_clock::now();
        developCpu(cfa.data(), info, meta, g);
        const auto t3 = std::chrono::steady_clock::now();
        if (i > 0) {
            off.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());
            on.push_back(std::chrono::duration<double, std::milli>(t3 - t2).count());
        }
    }
    const double dOn = medianOf(on), dOff = medianOf(off);
    std::printf("  off median=%.2fms  recovery-on median=%.2fms  delta=%.2fms  (deltas:", dOff, dOn, dOn - dOff);
    for (size_t i = 0; i < on.size(); ++i) std::printf(" %.1f", on[i] - off[i]);
    std::printf(")\n");
    return 0;
}

int main(int argc, char** argv) {
    if (argc >= 3 && std::strcmp(argv[1], "record") == 0) return modeRecord(argv[2]);
    if (argc >= 3 && std::strcmp(argv[1], "verify") == 0) return modeVerify(argv[2]);
    if (argc >= 2 && std::strcmp(argv[1], "bench") == 0) {
        const uint32_t w = argc > 2 ? uint32_t(std::strtoul(argv[2], nullptr, 10)) : 4096;
        const uint32_t h = argc > 3 ? uint32_t(std::strtoul(argv[3], nullptr, 10)) : 3072;
        const int reps = argc > 4 ? std::atoi(argv[4]) : 5;
        return modeBench(w, h, reps);
    }
    if (argc >= 4 && std::strcmp(argv[1], "benchdev") == 0) {
        const size_t frame = size_t(std::strtoull(argv[3], nullptr, 10));
        const int reps = argc > 4 ? std::atoi(argv[4]) : 3;
        return modeBenchDev(argv[2], frame, reps);
    }
    std::fprintf(stderr,
        "usage:\n  hr_equivalence record <dir>\n  hr_equivalence verify <dir>\n"
        "  hr_equivalence bench [w h reps]\n  hr_equivalence benchdev <clip.mcraw> <frame> [reps]\n");
    return 2;
}
