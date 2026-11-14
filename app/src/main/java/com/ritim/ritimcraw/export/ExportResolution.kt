package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.RotationState
import kotlin.math.roundToInt

/**
 * THE export resolution rules (owner request 2026-11-13 ~17:30: "export
 * ederken 4K, FHD vs cozunurluk ya da orijinal videonun cozunurlugune gore
 * bir oneri yok" — exports need a resolution choice + a recommendation
 * based on the source). Pure, JVM-testable, no Android imports — the
 * TimelineExportPlan precedent: this object is the single decision point
 * for "what size does this export come out as", and both the sheet's
 * chips and the exporter's encoder dims consume it, so the two can never
 * disagree about the size the way RotationState prevents orientation
 * disagreement.
 *
 * THE LADDER: Original / UHD 3840x2160 / FHD 1920x1080 / HD 1280x720 /
 * Custom w+h. The RECOMMENDED default is Original — the source's
 * EFFECTIVE size (rotation + crop applied), exactly what today's export
 * produces; every other entry carries its honest DOWNSCALE/EQUAL/UPSCALE
 * flag so an upscale is EXPLICITLY marked, never the default.
 *
 * THE EXPERT'S RULES, honored here (2026-11-13 audit, embedded in the
 * mission brief):
 *  - DCI 4096x2160 "Original" and 3840x2160 "UHD" are DIFFERENT options,
 *    never aliases: a DCI source's UHD entry resolves to 3840x2024
 *    (fit-within), visibly smaller than its Original 4096x2160.
 *  - The SIZE decision and the FRAMING decision are separate: a 4:3
 *    source picking FHD resolves to 1440x1080 — its own aspect at the
 *    FHD bound — NEVER a center-crop to 1920x1080. No aspect forcing
 *    anywhere in this file: the output ALWAYS keeps the source's aspect.
 *  - Vertical sources orient correctly: the ladder's canonical boxes are
 *    landscape, so a PORTRAIT source transposes the box (UHD means
 *    2160x3840 there) and the resolved dims come out portrait.
 *  - Codec support is a SEAM: [CodecSupport] answers "can this device's
 *    encoder take w x h at fps". The caller wires MediaCodecInfo
 *    (isSizeSupported / areSizeAndRateSupported + width/height
 *    alignment) on device; JVM tests inject. A refusal DISABLES the
 *    entry at choice time (the visible failure); the encode itself
 *    already fails loudly through the writer's MediaCodec path and can
 *    never silently become another size. [Kind.ORIGINAL] is never
 *    codec-gated: it is today's behavior, and the device's verdict on
 *    it arrives honestly at encode time exactly as it always has.
 *  - No speed promises: the develop still runs at source resolution —
 *    a smaller output changes the ENCODE size, not the develop cost.
 *
 * REUSE, NOT DUPLICATION: the effective size composes the existing math —
 * RotationState.effectiveDeg + rotatedDims for orientation, then
 * CropState.outputDims(even = true) for the crop — the same chain
 * ExportPipeline runs, so "Original" here is byte-identical to what the
 * exporter would have produced with no resolution chosen.
 */
object ExportResolution {

    /**
     * One ladder choice. [Kind.ORIGINAL] resolves to the source's
     * effective size; the others are bounds (see [outputSize]).
     * [recommended] is true only on Original — the source-based
     * recommendation the owner asked for. [supported] = false means the
     * device codec seam refused this size/rate and the UI renders the
     * entry disabled; it is never false for [Kind.ORIGINAL].
     */
    data class Choice(
        val kind: Kind,
        val width: Int,
        val height: Int,
        val scale: Scale,
        val recommended: Boolean,
        val supported: Boolean,
    )

    /** The ladder's fixed entries; CUSTOM carries user-typed dims. */
    enum class Kind { ORIGINAL, UHD, FHD, HD, CUSTOM }

    /** The honest relation of an entry's output to the source size. */
    enum class Scale { DOWNSCALE, EQUAL, UPSCALE }

    /**
     * The codec support seam: "can this device's encoder take width x
     * height at fps". The device implementation composes
     * MediaCodecInfo.isSizeSupported / areSizeAndRateSupported and the
     * codec's width/height alignment (the universal 2-pixel alignment is
     * already handled by the even-snap here); JVM tests inject fakes.
     * Null means "no seam provided" — no entry is gated, and the
     * encode-time MediaCodec failure remains the honest backstop.
     */
    fun interface CodecSupport {
        fun isSizeAndRateSupported(width: Int, height: Int, fps: Double): Boolean
    }

    /**
     * The source's EFFECTIVE output size — the crop/rotation-applied size
     * today's export already produces, via the existing math (reused, not
 * duplicated): RotationState.effectiveDeg composes the container and
     * manual rotation, rotatedDims swaps on quarter turns, and
     * CropState.outputDims(even = true) applies the crop rect with the
     * encoder's even snap. This is what "Original" means on the ladder.
     */
    fun effectiveSourceSize(
        sourceWidth: Int,
        sourceHeight: Int,
        orientationDeg: Int,
        manualRotationDeg: Int,
        cropX: Float,
        cropY: Float,
        cropW: Float,
        cropH: Float,
    ): RotationState.Dims {
        val deg = RotationState.effectiveDeg(orientationDeg, manualRotationDeg)
        val upright = RotationState.rotatedDims(sourceWidth, sourceHeight, deg)
        val rect = CropState.RectF(cropX, cropY, cropW, cropH)
        return CropState.outputDims(upright.width, upright.height, rect, even = true)
    }

    /**
     * The output size one ladder entry resolves to for [source] (the
     * effective size from [effectiveSourceSize]).
     *
     * ORIGINAL returns [source] unchanged. UHD/FHD/HD are canonical
     * LANDSCAPE bounds, transposed for a portrait source (vertical
     * sources orient correctly); the entry means "FITS WITHIN the bound,
     * aspect preserved": the output scales by the bound's limiting axis,
     * so a 4:3 source at FHD resolves to 1440x1080 — its own aspect at
     * the FHD height, never a 16:9 crop. CUSTOM uses the typed dims as
     * the bound the same way (a 4:3 source with custom 1920x1080 also
     * resolves to 1440x1080 — no aspect forcing, no stretching, no
     * bars). Both axes even-snap for the encoder (CropState.snapEven).
     */
    fun outputSize(
        kind: Kind,
        source: RotationState.Dims,
        customWidth: Int = 0,
        customHeight: Int = 0,
    ): RotationState.Dims {
        require(source.width > 0 && source.height > 0) {
            "source ${source.width}x${source.height} is not a usable video size"
        }
        if (kind == Kind.ORIGINAL) return source
        val box = when (kind) {
            Kind.UHD -> orientedBox(UHD_LONG_EDGE, UHD_SHORT_EDGE, source)
            Kind.FHD -> orientedBox(FHD_LONG_EDGE, FHD_SHORT_EDGE, source)
            Kind.HD -> orientedBox(HD_LONG_EDGE, HD_SHORT_EDGE, source)
            Kind.CUSTOM -> {
                require(customWidth > 0 && customHeight > 0) {
                    "custom resolution ${customWidth}x$customHeight is not a usable bound"
                }
                RotationState.Dims(customWidth, customHeight)
            }
            Kind.ORIGINAL -> source // unreachable: handled above
        }
        val scale = minOf(
            box.width.toDouble() / source.width,
            box.height.toDouble() / source.height,
        )
        return RotationState.Dims(
            CropState.snapEven((source.width * scale).roundToInt()),
            CropState.snapEven((source.height * scale).roundToInt()),
        )
    }

    /**
     * The honest relation of [output] to [source]: UPSCALE when either
     * axis grows, EQUAL when both match, DOWNSCALE otherwise. Integer-
     * exact on the resolved dims — no float tolerance games.
     */
    fun classify(source: RotationState.Dims, output: RotationState.Dims): Scale = when {
        output.width > source.width || output.height > source.height -> Scale.UPSCALE
        output.width == source.width && output.height == source.height -> Scale.EQUAL
        else -> Scale.DOWNSCALE
    }

    /**
     * Resolves one entry against [source]: its output size, its honest
     * scale flag, whether it is the recommendation (Original only), and
     * the codec seam's verdict at that size and [fps] (the source's
     * measured rate). [fps] is only consulted for the seam; pass the
     * honest measured value, and pass codec = null when there is none.
     */
    fun choice(
        kind: Kind,
        source: RotationState.Dims,
        fps: Double,
        codec: CodecSupport? = null,
        customWidth: Int = 0,
        customHeight: Int = 0,
    ): Choice {
        val out = outputSize(kind, source, customWidth, customHeight)
        val supported = kind == Kind.ORIGINAL ||
            codec == null ||
            codec.isSizeAndRateSupported(out.width, out.height, fps)
        return Choice(
            kind = kind,
            width = out.width,
            height = out.height,
            scale = classify(source, out),
            recommended = kind == Kind.ORIGINAL,
            supported = supported,
        )
    }

    /**
     * The fixed ladder [Choice]s for [source]: Original (recommended),
     * UHD, FHD, HD — each with its resolved size, honest scale flag, and
     * codec verdict. The sheet's Custom entry resolves through
     * [choice] when its fields hold values.
     */
    fun ladder(
        source: RotationState.Dims,
        fps: Double,
        codec: CodecSupport? = null,
    ): List<Choice> =
        listOf(Kind.ORIGINAL, Kind.UHD, Kind.FHD, Kind.HD)
            .map { choice(it, source, fps, codec) }

    // ---- internals ----

    /**
     * A canonical bound (long x short edge, landscape by definition)
     * oriented for the source: a PORTRAIT source transposes it so the
     * long edge stays the output's long edge (vertical video orients
     * correctly); a landscape source keeps it as-is.
     */
    private fun orientedBox(
        longEdge: Int,
        shortEdge: Int,
        source: RotationState.Dims,
    ): RotationState.Dims =
        if (source.height > source.width) {
            RotationState.Dims(shortEdge, longEdge)
        } else {
            RotationState.Dims(longEdge, shortEdge)
        }

    private const val UHD_LONG_EDGE = 3840
    private const val UHD_SHORT_EDGE = 2160
    private const val FHD_LONG_EDGE = 1920
    private const val FHD_SHORT_EDGE = 1080
    private const val HD_LONG_EDGE = 1280
    private const val HD_SHORT_EDGE = 720
}
