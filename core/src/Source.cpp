#include <rawedit/Source.h>

#include <motioncam/Decoder.hpp>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <utility>
#include <unistd.h>

namespace rawedit {

// One Source owns one motioncam::Decoder. The decoder is not safe for
// concurrent use, so neither is a Source (see Source.h).
struct SourceImpl {
    std::unique_ptr<motioncam::Decoder> decoder;
    RawMeta meta;
    CfaPattern cfa = CfaPattern::RGGB;
    int sensorOrientation = 0;
};

namespace {

// Maps a caught decoder exception onto an ErrorCode with a stage-prefixed
// detail (spec SYS-02: name the stage, the cause and the recovery).
// The decoder throws IOException for: bad container id/version, corrupt
// index, short reads, invalid offsets. Distinguish header-level failures
// (BadHeader) from mid-file data failures (Truncated) so callers can tell
// "not an MCRAW at all" from "an MCRAW cut short".
ErrorCode classifyError(const std::string& what) {
    if (what.find("Invalid header id") != std::string::npos
        || what.find("Invalid container version") != std::string::npos
        || what.find("Invalid camera metadata") != std::string::npos
        || what.find("Failed to open") != std::string::npos
        || what.find("Invalid file descriptor") != std::string::npos
        || what.find("failed to open") != std::string::npos) {
        return ErrorCode::BadHeader;
    }
    return ErrorCode::Truncated;
}

// Reads one JSON key into `out`, leaving it unchanged when the key is absent.
template <class T>
void getJson(const nlohmann::json& j, const char* key, T& out) {
    const auto it = j.find(key);
    if (it != j.end() && !it->is_null())
        out = it->get<T>();
}

// Populates impl->meta / cfa / sensorOrientation from container metadata and
// the first frame's metadata. Returns false when the sensor arrangement is
// not one of the four supported patterns (the caller maps that to
// ErrorCode::Unsupported).
bool initMeta(SourceImpl& impl) {
    const nlohmann::json& cm = impl.decoder->getContainerMetadata();

    // CFA pattern. The key is "sensorArrangment" — the typo is in the format.
    std::string arrangement = "rggb";
    getJson(cm, "sensorArrangment", arrangement);
    ErrorCode code = ErrorCode::Ok;
    impl.cfa = parseCfaPattern(arrangement, code);
    if (code != ErrorCode::Ok)
        return false;

    // Levels.
    std::vector<double> black;
    getJson(cm, "blackLevel", black);
    for (int i = 0; i < 4 && i < static_cast<int>(black.size()); ++i)
        impl.meta.black[i] = static_cast<uint16_t>(black[i]);

    double white = 0.0;
    getJson(cm, "whiteLevel", white);
    impl.meta.white = static_cast<uint16_t>(white);

    // Color matrix (3x3 row-major, DNG direction XYZ -> camera RGB).
    std::vector<double> m1;
    getJson(cm, "colorMatrix1", m1);
    for (int i = 0; i < 9 && i < static_cast<int>(m1.size()); ++i)
        impl.meta.colorMatrix[i] = static_cast<float>(m1[i]);

    // Forward matrix (3x3 row-major, DNG direction camera RGB normalized by
    // asShotNeutral -> XYZ D50). Preferred by the DNG spec over the
    // ColorMatrix path when present; the develop stage needs it to build
    // the camera -> display transform correctly.
    std::vector<double> fm1;
    getJson(cm, "forwardMatrix1", fm1);
    for (int i = 0; i < 9 && i < static_cast<int>(fm1.size()); ++i)
        impl.meta.forwardMatrix[i] = static_cast<float>(fm1[i]);

    // Calibration matrix (3x3 row-major, DNG CalibrationMatrix1; usually
    // near-diagonal per-illuminant calibration).
    std::vector<double> cal1;
    getJson(cm, "calibrationMatrix1", cal1);
    for (int i = 0; i < 9 && i < static_cast<int>(cal1.size()); ++i)
        impl.meta.calibration[i] = static_cast<float>(cal1[i]);

    // Sensor orientation (container level; per-frame orientation overrides
    // it in frameInfo()).
    int orient = 0;
    getJson(cm, "sensorOrientation", orient);
    impl.sensorOrientation = orient;

    // White balance from the first frame's asShotNeutral: gains are the
    // reciprocal of the neutral vector (DNG semantics). Each field is parsed
    // and type-checked independently, so one malformed field cannot silently
    // discard the rest.
    const auto& frames = impl.decoder->getFrames();
    if (!frames.empty()) {
        nlohmann::json fm;
        try {
            impl.decoder->loadFrameMetadata(frames[0], fm);
        } catch (...) {
            // First-frame metadata is optional for rawMeta();
            // frameInfo()/readFrame() surface their own errors.
            fm = nlohmann::json::object();
        }

        if (const auto it = fm.find("asShotNeutral");
            it != fm.end() && it->is_array()) {
            const auto neutral = it->get<std::vector<double>>();
            for (int i = 0; i < 3 && i < static_cast<int>(neutral.size()); ++i) {
                const double n = neutral[i];
                impl.meta.asShotNeutral[i] = static_cast<float>(n);
                impl.meta.wbGains[i] = n > 0.0 ? static_cast<float>(1.0 / n) : 1.0f;
            }
        }

        // Lens shading map: 4 planes, each shadingH x shadingW, row-major.
        // The container stores it as a nested array — [plane][row*sw+col]
        // — so it must be flattened plane by plane.
        int sw = 0, sh = 0;
        getJson(fm, "lensShadingMapWidth", sw);
        getJson(fm, "lensShadingMapHeight", sh);
        const size_t plane = static_cast<size_t>(sw) * static_cast<size_t>(sh);
        const auto shadingIt = fm.find("lensShadingMap");
        if (sw > 0 && sh > 0 && shadingIt != fm.end() && shadingIt->is_array()) {
            const auto& planes = *shadingIt;
            if (planes.size() >= 4 && planes[0].is_array()
                && planes[0].size() == plane) {
                impl.meta.shadingW = static_cast<uint32_t>(sw);
                impl.meta.shadingH = static_cast<uint32_t>(sh);
                impl.meta.lensShading.assign(4 * plane, 0.0f);
                for (int p = 0; p < 4; ++p) {
                    const auto& row = planes[static_cast<size_t>(p)];
                    if (!row.is_array())
                        continue;
                    const size_t n = std::min(plane, row.size());
                    for (size_t k = 0; k < n; ++k)
                        if (row[k].is_number())
                            impl.meta.lensShading[static_cast<size_t>(p) * plane + k] =
                                row[k].get<float>();
                }
            }
        }
    }

    return true;
}

} // namespace

CfaPattern parseCfaPattern(const std::string& sensorArrangment, ErrorCode& code) {
    code = ErrorCode::Ok;
    if (sensorArrangment == "rggb") return CfaPattern::RGGB;
    if (sensorArrangment == "grbg") return CfaPattern::GRBG;
    if (sensorArrangment == "gbrg") return CfaPattern::GBRG;
    if (sensorArrangment == "bggr") return CfaPattern::BGGR;
    code = ErrorCode::Unsupported;
    return CfaPattern::RGGB;
}

Result<std::unique_ptr<Source>> Source::open(int fd) {
    if (fd < 0)
        return Result<std::unique_ptr<Source>>::fail(ErrorCode::BadHeader,
            "open: invalid file descriptor (recovery: pass a descriptor opened for reading)");

    const int dupFd = ::dup(fd);
    if (dupFd < 0)
        return Result<std::unique_ptr<Source>>::fail(ErrorCode::IoError,
            "open: could not duplicate file descriptor (recovery: check the descriptor is open)");

    FILE* file = ::fdopen(dupFd, "rb");
    if (!file) {
        ::close(dupFd);
        return Result<std::unique_ptr<Source>>::fail(ErrorCode::IoError,
            "open: fdopen failed (recovery: check available file descriptors)");
    }

    std::unique_ptr<SourceImpl> impl;
    try {
        impl = std::make_unique<SourceImpl>();
        // Decoder(FILE*) takes ownership of `file` on success AND on failure
        // (its catch block closes the FILE* before rethrowing).
        impl->decoder = std::make_unique<motioncam::Decoder>(file);
        if (!initMeta(*impl)) {
            return Result<std::unique_ptr<Source>>::fail(ErrorCode::Unsupported,
                "open: unsupported sensor arrangement (recovery: this build supports rggb/grbg/gbrg/bggr)");
        }
    } catch (const std::exception& e) {
        return Result<std::unique_ptr<Source>>::fail(classifyError(e.what()),
            std::string("open: ") + e.what() + " (recovery: verify the file is a complete MotionCam recording)");
    } catch (...) {
        return Result<std::unique_ptr<Source>>::fail(ErrorCode::IoError,
            "open: unknown decoder error (recovery: verify the file is a complete MotionCam recording)");
    }

    Source* s = new Source();
    s->mImpl = impl.release();
    return Result<std::unique_ptr<Source>>::ok(std::unique_ptr<Source>(s));
}

Result<std::unique_ptr<Source>> Source::open(const std::string& path) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file)
        return Result<std::unique_ptr<Source>>::fail(ErrorCode::BadHeader,
            "open: failed to open " + path + " (recovery: check the path and read permission)");
    const int fd = ::fileno(file);

    auto res = Source::open(fd);
    // Source::open duped the fd; close the original FILE* either way.
    std::fclose(file);
    return res;
}

Source::~Source() {
    delete mImpl;
}

size_t Source::frameCount() const {
    return mImpl->decoder->getFrames().size();
}

FrameInfo Source::frameInfo(size_t i) const {
    FrameInfo info{};

    const auto& frames = mImpl->decoder->getFrames();
    if (i >= frames.size())
        return info;

    nlohmann::json meta;
    try {
        mImpl->decoder->loadFrameMetadata(frames[i], meta);
    } catch (...) {
        // Metadata-only load failed; report a zeroed frame rather than
        // throwing. readFrame() will surface the real error for this frame.
        return info;
    }

    int width = 0, height = 0;
    getJson(meta, "width", width);
    getJson(meta, "height", height);
    info.width = static_cast<uint32_t>(width);
    info.height = static_cast<uint32_t>(height);
    info.bitDepth = 16;

    // Frame timestamps are nanoseconds (Container BufferOffset.timestamp);
    // FrameInfo carries microseconds.
    info.ptsUs = frames[i] / 1000;

    info.cfa = mImpl->cfa;

    // QA-02: the container format has no separate active rect — the recorded
    // CFA is already the crop the recorder wrote (e.g. 3840x2160 out of a
    // 4096x3072 sensor; originalWidth/originalHeight stay in the frame JSON).
    // Use the full frame until a real padded fixture lands.
    info.activeArea = Rect{0, 0, width, height};

    int orientation = mImpl->sensorOrientation;
    getJson(meta, "orientation", orientation);
    info.orientationDeg = orientation;

    // Per-frame RAW levels (owner request 2026-11-11 section 3): Android
    // Camera2 defines dynamic black/white levels as per-frame RAW
    // calibration. The frame JSON carries them as plain integers; the
    // sentinel -1 (FrameInfo's default) means "this frame did not report a
    // level" and the consumers (develop stage, DNG writer) fall back to the
    // container RawMeta values. Invalid values (negative, or a white at or
    // below black) also leave the sentinel in place — a bogus level never
    // reaches the develop stage.
    std::vector<double> dynBlack;
    getJson(meta, "dynamicBlackLevel", dynBlack);
    if (dynBlack.size() == 4) {
        bool valid = true;
        for (double v : dynBlack) {
            if (v < 0.0 || v > 65535.0) {
                valid = false;
                break;
            }
        }
        if (valid)
            for (int k = 0; k < 4; ++k)
                info.dynamicBlack[k] = static_cast<int32_t>(dynBlack[k]);
    }
    double dynWhite = -1.0;
    getJson(meta, "dynamicWhiteLevel", dynWhite);
    if (dynWhite > 0.0 && dynWhite <= 65535.0)
        info.dynamicWhite = static_cast<int32_t>(dynWhite);
    // A white at or below the (frame or container) black is invalid RAW
    // calibration, not a level to trust: drop it back to the sentinel.
    const int32_t blackFloor = info.dynamicBlack[0] >= 0
        ? info.dynamicBlack[0]
        : static_cast<int32_t>(mImpl->meta.black[0]);
    if (info.dynamicWhite >= 0 && info.dynamicWhite <= blackFloor)
        info.dynamicWhite = -1;

    return info;
}

NoiseProfile Source::noiseProfile(size_t i) const {
    NoiseProfile np{};

    const auto& frames = mImpl->decoder->getFrames();
    if (i >= frames.size())
        return np; // valid=false: out-of-range degrades to the fallback sigma

    nlohmann::json meta;
    try {
        mImpl->decoder->loadFrameMetadata(frames[i], meta);
    } catch (...) {
        // Same policy as frameInfo: a metadata-only failure reports the
        // absence (valid=false) instead of throwing; the consumer's
        // fallback sigma keeps the render alive.
        return np;
    }

    // "noiseProfile": [S0,O0, S1,O1, S2,O2, S3,O3] — Camera2
    // SENSOR_NOISE_PROFILE per CFA slot, normalized-signal domain, in the
    // slot order of RawMeta::black (r, g, g, b). Requires all 8 numbers;
    // anything short or malformed stays valid=false (fallback sigma).
    std::vector<double> prof;
    getJson(meta, "noiseProfile", prof);
    if (prof.size() == 8) {
        np.valid = true;
        for (int k = 0; k < 4; ++k) {
            np.s[k] = static_cast<float>(prof[2 * k]);
            np.o[k] = static_cast<float>(prof[2 * k + 1]);
        }
    }
    return np;
}

Result<std::vector<uint16_t>> Source::readFrame(size_t i) {
    const auto& frames = mImpl->decoder->getFrames();
    if (i >= frames.size())
        return Result<std::vector<uint16_t>>::fail(ErrorCode::Unsupported,
            "readFrame: index " + std::to_string(i) + " out of range (frameCount="
            + std::to_string(frames.size()) + ", recovery: use an index < frameCount)");

    std::vector<uint8_t> data;
    nlohmann::json meta;
    try {
        mImpl->decoder->loadFrame(frames[i], data, meta);
    } catch (const std::exception& e) {
        return Result<std::vector<uint16_t>>::fail(classifyError(e.what()),
            std::string("readFrame: ") + e.what() + " (recovery: re-record or re-transfer the clip)");
    } catch (...) {
        return Result<std::vector<uint16_t>>::fail(ErrorCode::IoError,
            "readFrame: unknown decoder error (recovery: re-record or re-transfer the clip)");
    }

    // loadFrame returns a w*h uint16 CFA buffer; copy out as uint16.
    std::vector<uint16_t> pixels(data.size() / sizeof(uint16_t));
    if (!data.empty())
        std::memcpy(pixels.data(), data.data(), data.size());
    return Result<std::vector<uint16_t>>::ok(std::move(pixels));
}

Result<AudioInfo> Source::audioInfo() const {
    AudioInfo info{};
    try {
        info.sampleRate = mImpl->decoder->audioSampleRateHz();
        info.channels = mImpl->decoder->numAudioChannels();
    } catch (const std::exception& e) {
        return Result<AudioInfo>::fail(classifyError(e.what()),
            std::string("audioInfo: ") + e.what() + " (recovery: the clip may have no audio track)");
    } catch (...) {
        return Result<AudioInfo>::fail(ErrorCode::IoError, "audioInfo: unknown decoder error");
    }
    return Result<AudioInfo>::ok(info);
}

Result<std::vector<int16_t>> Source::readAudio() {
    std::vector<motioncam::AudioChunk> chunks;
    try {
        mImpl->decoder->loadAudio(chunks);
    } catch (const std::exception& e) {
        return Result<std::vector<int16_t>>::fail(classifyError(e.what()),
            std::string("readAudio: ") + e.what() + " (recovery: re-record or re-transfer the clip)");
    } catch (...) {
        return Result<std::vector<int16_t>>::fail(ErrorCode::IoError,
            "readAudio: unknown decoder error (recovery: re-record or re-transfer the clip)");
    }

    size_t total = 0;
    for (const auto& c : chunks)
        total += c.second.size();

    std::vector<int16_t> samples;
    samples.reserve(total);
    for (auto& c : chunks)
        samples.insert(samples.end(), c.second.begin(), c.second.end());
    return Result<std::vector<int16_t>>::ok(std::move(samples));
}

const RawMeta& Source::rawMeta() const {
    return mImpl->meta;
}

} // namespace rawedit
