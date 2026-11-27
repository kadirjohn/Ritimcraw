package com.ritim.ritimcraw.export.hevc

/**
 * THE trial runner seam (G4): the short HLG verify encode the API-34
 * fallback path needs before an HLG plan is trusted. The DECISION layer
 * ([HevcTrialEncode.decide]) is pure and JVM-tested with a FAKE runner;
 * THIS interface is the device leg's contract — the one implementation
 * that touches MediaCodec ([MediaCodecHevcTrialRunner]) is device-gated
 * by nature (framework stubs throw on the JVM) and every claim it makes
 * carries the "not verified off-device" stamp.
 *
 * THE CONTRACT (the plan's own trial sequence, the HevcPreflight KDoc):
 * configure the encoder with the profile's FULL MediaFormat contract at
 * the job's size, start it, feed a HANDFUL of frames, end the stream and
 * RE-DECODE the output — the verdict is what the OUTPUT carries (the
 * profile on the decoder's format, the color tags on the bitstream's
 * VUI), never what was requested. A configure/start/decode failure is a
 * [HevcTrialEncode.Result.Refused] with the stage's own sentence —
 * never a crash and never a silent pass.
 */
fun interface HevcTrialRunner {
    /**
     * Runs one short trial encode for [request]. Runs on the export
     * engine thread (the same thread the writer configures on), takes
     * at most a few hundred milliseconds on a healthy device, and is
     * expected to be called AT MOST ONCE per (profile, size, codec,
     * build) — the cache layer above it holds that invariant; a second
     * call with the same shape is a cache miss to investigate, not an
     * error to hide (the runner itself stays stateless and re-runnable).
     */
    fun run(request: Request): HevcTrialEncode.Result

    /** One trial's full ask: the profile's contract at the job's shape. */
    data class Request(
        val profile: HevcProfile,
        val width: Int,
        val height: Int,
        val bitrateBps: Int,
        val fps: Double,
        val iFrameIntervalSec: Float,
        /** The encoder the verdict named (createByCodecName), or null = by MIME. */
        val encoderName: String?,
    )
}
