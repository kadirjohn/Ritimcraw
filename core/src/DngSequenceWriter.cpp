// rawedit::DngSequenceWriter — see rawedit/DngSequenceWriter.h for the
// interface contract (writer half of docs/CINEMADNG_EXPORT_PLAN.md R1-2;
// engine/DngSequence.kt is the unrelated reader-side pure model).

#include <rawedit/DngSequenceWriter.h>

#include <cstdio>
#include <string>
#include <vector>

namespace rawedit {

std::string DngSequencePlan::fileName(size_t i) const {
    // Width clamp: the caller may ask for anything; the plan is usable
    // in [1, 10]. A wider ask is a valid() rejection, but the name
    // helper still behaves (never a crash, never a wrong number).
    int digits = naming.frameDigits;
    if (digits < 1) digits = 1;
    if (digits > 10) digits = 10;
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%s_%0*zu.dng",
                  naming.prefix.c_str(), digits, i);
    return std::string(buf);
}

void DngSequencePlan::timeCodesOf(size_t i, uint8_t out[8]) const {
    smpteTimeCodes(startTimecode.hour, startTimecode.minute,
                   startTimecode.second, startTimecode.frame,
                   static_cast<int>(i), fps(), out);
}

bool DngSequencePlan::valid(std::string& why) const {
    if (fpsNum == 0 || fpsDen == 0) {
        why = "fps rational has a zero term";
        return false;
    }
    if (naming.frameDigits < 1 || naming.frameDigits > 10) {
        why = "frameDigits must be in [1, 10]";
        return false;
    }
    if (naming.prefix.empty()) {
        why = "naming prefix is empty";
        return false;
    }
    for (char c : naming.prefix) {
        if (c == '/' || c == '\\' || c == '\0') {
            why = "naming prefix holds a path separator";
            return false;
        }
    }
    if (outputDir.empty()) {
        why = "outputDir is empty";
        return false;
    }
    if (startTimecode.hour < 0 || startTimecode.hour > 23 ||
        startTimecode.minute < 0 || startTimecode.minute > 59 ||
        startTimecode.second < 0 || startTimecode.second > 59 ||
        startTimecode.frame < 0 || startTimecode.frame > 59) {
        why = "startTimecode is out of SMPTE range";
        return false;
    }
    return true;
}

SequenceResult writeDngSequence(
    const DngSequencePlan& plan,
    const RawMeta& meta,
    const DngExtraMeta& extra,
    const SequenceFrameProvider& provider,
    const SequenceCancelProbe& shouldCancel,
    const SequenceProgress& onProgress) {

    SequenceResult result;
    result.framesTotal = plan.frameCount;

    std::string why;
    if (!plan.valid(why) || provider == nullptr) {
        result.state = SequenceState::Failed;
        result.failCode = static_cast<int>(ErrorCode::Unsupported);
        result.failStage = "sequence plan";
        result.failDetail = why.empty() ? "no frame provider" : why;
        return result;
    }

    for (size_t i = 0; i < plan.frameCount; ++i) {
        if (shouldCancel && shouldCancel()) {
            // The frames already written STAY on disk: a partial result
            // is a result (the plan's storage rule, never a silent
            // delete). The caller reports the partial set.
            result.state = SequenceState::Cancelled;
            return result;
        }

        // Decode the frame through the provider (the provider runs on
        // THIS thread, one at a time — the motioncam decoder is not
        // concurrency-safe, the ExportEngine single-thread contract).
        SequenceFrameData data;
        int code = 0;
        std::string stage;
        std::string detail;
        if (!provider(i, data, code, stage, detail)) {
            result.state = SequenceState::Failed;
            result.failCode = code;
            result.failStage = stage.empty() ? "frame read" : stage;
            result.failDetail = detail;
            return result;
        }

        // The per-frame sequence meta: this frame's SMPTE code and the
        // EXACT fps rational (the tag and the arithmetic share the
        // plan's one source of truth).
        DngSequenceMeta seq;
        seq.hasTimeCodes = true;
        plan.timeCodesOf(i, seq.timeCodes);
        seq.hasFrameRate = true;
        seq.frameRateNum = plan.fpsNum;
        seq.frameRateDen = plan.fpsDen;

        const std::string path = plan.outputDir + "/" + plan.fileName(i);
        const Result<void> write =
            writeCfaDng(path, data.cfa.data(), data.frame, meta, extra, seq);
        if (!write.isOk()) {
            result.state = SequenceState::Failed;
            result.failCode = static_cast<int>(write.code);
            result.failStage = "dng write";
            result.failDetail = write.detail;
            return result;
        }

        result.writtenPaths.push_back(path);
        result.framesWritten = result.writtenPaths.size();
        if (onProgress) onProgress(result.framesWritten, plan.frameCount);
    }

    result.state = SequenceState::Complete;
    result.framesWritten = result.writtenPaths.size();
    return result;
}

} // namespace rawedit
