package com.ritim.ritimcraw.export

import android.media.MediaCodecInfo
import com.ritim.ritimcraw.export.hevc.HevcPreflight
import com.ritim.ritimcraw.export.hevc.HevcProfile

/**
 * THE pure bridge between the export side's probed [EncoderFacts] (E1b,
 * DeviceEncoderFacts) and the HLG preflight's [HevcPreflight.HevcProbeResult]
 * (E2-a, package G4 — the first wiring of the pure layer into the real
 * export flow). Nothing here queries a device: every field is copied from
 * facts the device layer ALREADY probes (MediaCodecList's profileLevels,
 * color formats, the feature strings, areSizeAndRateSupported), so the
 * decision above it stays JVM-tested with SYNTHETIC facts and the device
 * leg is exactly one pure function.
 *
 * WHY A SEPARATE FILE: the probe facts and the preflight verdict use two
 * deliberately different vocabularies. EncoderFacts answers "which encoder
 * does the planner pick for this job" (best-first, size/rate, CQ variants
 * filtered by role); HevcProbeResult answers "does THIS profile's own gate
 * hold on this device" (codec-list -> profile -> feature -> size/rate, in
 * that fixed order). Folding one into the other would change the E1
 * planner's behavior; this bridge only READS both and builds the verdict's
 * input, never the plan.
 *
 * THE SIZE/RATE FIELD is the one honest approximation: the preflight wants
 * one boolean for the JOB's size at the JOB's fps, and the encoder facts
 * hold a live probe ([EncoderFacts.sizeRate]). The bridge takes the
 * width/height/fps as parameters so the caller passes the SAME resolved
 * encode dims the planner will (portrait transposed dims ride the probe's
 * own both-orientations rule, the DeviceEncoderFacts shape). A null probe
 * (no live facts) maps to sizeRateAccepted = TRUE — never a silent
 * refusal: the preflight's SIZE_RATE refusal is reserved for a probe that
 * SAID no, and the export itself re-checks the size in writer.start.
 *
 * HDR/HLG feature strings: DeviceEncoderFacts probes exactly the two the
 * gates consult ("hdr-editing", FEATURE_HdrEditing; "hlg-editing" — the
 * string query, the android-34 jar carries no constant). The bridge maps
 * the chosen HEVC encoder's feature SET booleans; an unknown feature name
 * is false in the probe result (HevcProbeResult's own rule).
 */
object HevcProbeSource {

    /**
     * Builds the HLG preflight's probe result from the device facts the
     * export already holds. [width]/[height]/[fps] are the job's resolved
     * ENCODE size and the clip's own fps — the same inputs the planner
     * resolves against, so the verdict's size/rate stage and the plan's
     * encoder pick can never disagree about which shape was asked.
     *
     * The HEVC encoder chosen is the planner's own best-first pick for an
     * HLG-shaped request (Main10 10-bit HEVC, GENERAL role): HLG IS a
     * Main10/HEVC job, so the encoder the verdict names is the encoder
     * the plan would name. No facts (the probe failed / a JVM test) ->
     * a probe result that refuses at the codec-list stage — the honest
     * "nothing was measured" shape, never an assumed-support verdict.
     */
    fun fromFacts(
        facts: List<EncoderFacts>,
        width: Int,
        height: Int,
        fps: Double,
        apiLevel: Int,
    ): HevcPreflight.HevcProbeResult {
        val hevc = pickHevc(facts, width, height, fps)
        val avc = pickAvc(facts, width, height, fps)
        val hlgSupported = hevc?.features?.contains("hlg-editing") == true
        val hdrSupported = hevc?.features?.contains(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing) == true
        return HevcPreflight.HevcProbeResult(
            hevcEncoderName = hevc?.name,
            listedProfiles = hevc?.profiles?.toList() ?: emptyList(),
            acceptsP010 = hevc?.colorFormats?.contains(EncoderFacts.P010) == true,
            sizeRateAccepted = hevc == null || hevc.sizeSupport(width, height, fps) != Support.NO,
            deviceApiLevel = apiLevel,
            hdrEditingSupported = hdrSupported,
            // Below API 35 the device leg reports false WITHOUT querying
            // (HevcProbeResult's rule); the string query is harmless, but
            // the guard keeps the report honest about what was asked.
            hlgEditingSupported = hlgSupported && apiLevel >= HevcProfile.HLG.feature!!.requiredApiLevel,
            avcEncoderName = avc?.name,
        )
    }

    /**
     * The planner's own best-first pick narrowed to the HLG shape: a
     * GENERAL-role video/hevc encoder that takes 10-bit input and lists
     * Main10. Reuses [ExportPlanner]'s ordering by delegating to the same
     * comparator fields (verified size first, hardware, Codec2, ceiling,
     * name) so the verdict's codec name and the plan's encoderName agree.
     */
    private fun pickHevc(facts: List<EncoderFacts>, width: Int, height: Int, fps: Double): EncoderFacts? =
        facts.filter {
            it.mime == ExportCodec.HEVC.mime && it.role == EncoderFacts.Role.GENERAL &&
                it.acceptsTenBitInput() && it.supportsTenBitProfile(it.mime)
        }.minByOrNull { e ->
            // The planner's own order, expressed as a comparator key:
            // unverified size ranks last, then software, then OMX, then
            // the lower ceiling, then the name (stable).
            listOf(
                if (e.sizeSupport(width, height, fps) == Support.YES) 0 else 1,
                if (e.hardware) 0 else 1,
                if (e.isCodec2) 0 else 1,
                -(e.bitrateRangeBps?.last ?: 0),
            ).joinToString("|") { it.toString().padStart(12) } + "|" + e.name
        }

    /** The AVC baseline's pick (the DegradedOffer's probed name). */
    private fun pickAvc(facts: List<EncoderFacts>, width: Int, height: Int, fps: Double): EncoderFacts? =
        facts.filter { it.mime == ExportCodec.AVC.mime && it.role == EncoderFacts.Role.GENERAL }
            .minByOrNull { e ->
                listOf(
                    if (e.sizeSupport(width, height, fps) == Support.YES) 0 else 1,
                    if (e.hardware) 0 else 1,
                    if (e.isCodec2) 0 else 1,
                ).joinToString("|") { it.toString().padStart(12) } + "|" + e.name
            }
}
