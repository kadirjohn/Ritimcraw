package com.ritim.ritimcraw.data.grade2

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.ProjectJson

/** One float as the wire writes it (the v1 renderer's own rule). */
internal fun num(v: Float): String = v.toString()

/**
 * The schema-2 per-clip grade (color model v2 F1-3, docs/COLOR_MODEL_V2_PLAN.md
 * §3.1/§5/§6): the wire shape behind `MIGRATIONS[0]`'s output and the model
 * the NODES stage (F1-5/f1-6) will consume. v1's flat GradeK record folds into
 * four nested groups:
 *
 *   { "v2": 2,
 *     "develop": { "exposureEv", "tempK", "tint", "asShot", "highlightReconstruction",
 *                  "keepVignetting", "noiseReduction", "chromaDenoise",
 *                  "temporalNr", "temporalStrength", "sharpness", "detail" },
 *     "legacy":  { "highlights", "blackPoint", "whitePoint", "shadows", "midtones",
 *                  "contrast", "saturation", wheel* (6), split* (3) },
 *     "nodes":   [ GradeNodeV2... ],
 *     "output":  { "mode": "legacy" | "opendrt",
 *                  "legacy": { "tonemap", "lutId", "lutStrength", "lutInterp"? },
 *                  "opendrt": { "look", "tonescale", "cwp", "cwpLimit", "grey", "display" } },
 *     "transform": { "cropAspect"? "cropX/Y/W/H"? "straightenDeg"?, "stab"? } }
 *
 * DESIGN RULES (the plan's, verbatim):
 *  - K2/K3: a MIGRATED v1 record is the SAME controls in new clothes —
 *    [toGradeK] reproduces the v1 GradeK bit for bit (the adapter proof, JVM
 *    test), so existing renders don't change pixels. `mode: legacy` is the
 *    ONLY output mode a migrated record carries.
 *  - K10: stabilization folds into `transform.stab` — { enabled, engine,
 *    mode, strength, zoomLimit, trimMs, focalMm }, written with ALL sub
 *    values when enabled (engine EXPLICIT, so the "absent key = gyro" rule
 *    never has to be re-derived by a v2 writer), never written when off
 *    (v1's omit-when-off, §7).
 *  - The grouped fields are number-for-number v1's: unit, range and default
 *    carry over unchanged (exposureEv stops, tempK Kelvin, strength 0..100,
 *    k 0.12 black point...). Grouping is a RENAMING of the wire shape, not a
 *    re-parameterization.
 *  - NODES (N1) records carry the knob set rawedit::GradeNode defines; a
 *    NEUTRAL node list (`nodes: []`) renders nothing but `nodes:[]`, an
 *    empty chain never adds a GPU pass (§3.2).
 *  - Render order is fixed (develop, legacy, nodes, output, transform) and
 *    key orders inside groups follow the field order, so a v2 record is
 *    byte-stable: same grade, same bytes (the v1 renderer's discipline).
 *
 * The field-by-field v1->v2 mapping lives in [GradeV2Migration] (the
 * migration step's own source of truth); THIS file owns the model, the
 * renderer and the per-field-tolerant parser.
 */

/** One node's color wheel: puck (x, y) + master, mirroring rawedit::NodeWheel. */
data class NodeWheelV2(
    val x: Float = 0f,
    val y: Float = 0f,
    val master: Float = 0f,
) {
    fun isNeutral(): Boolean = x == 0f && y == 0f && master == 0f
}

/** One curve: flat control-point pairs (x sorted ascending); `{0,0},{1,1}` = identity. */
data class NodeCurveV2(
    val points: List<Pair<Float, Float>> = listOf(0f to 0f, 1f to 1f),
) {
    fun isIdentity(): Boolean =
        points.size == 2 && points[0] == Pair(0f, 0f) && points[1] == Pair(1f, 1f)

    companion object {
        internal fun render(sb: StringBuilder, c: NodeCurveV2) {
            sb.append('[')
            c.points.forEachIndexed { i, (x, y) ->
                if (i > 0) sb.append(',')
                sb.append('[').append(num(x)).append(',').append(num(y)).append(']')
            }
            sb.append(']')
        }

        /**
         * Parses one curve: the wire writes `[x,y]` pairs (the renderer's
         * own shape). A curve this build cannot read (wrong types) parses
         * as EMPTY — the render will treat it as identity-shaped and the
         * record still loads (the row-tolerant rule).
         */
        internal fun parse(v: Any?): NodeCurveV2? {
            if (v == null) return null
            val arr = v as? List<*> ?: return NodeCurveV2(emptyList())
            val pts = ArrayList<Pair<Float, Float>>(arr.size)
            for (p in arr) {
                val pair = p as? List<*> ?: continue
                if (pair.size != 2) continue
                val x = (pair[0] as? Double)?.toFloat() ?: continue
                val y = (pair[1] as? Double)?.toFloat() ?: continue
                pts.add(x to y)
            }
            return NodeCurveV2(pts)
        }
    }
}

/**
 * One N1 node (plan §3.3; the knob set rawedit::GradeNode carries —
 * primaries, log wheels, curves, node LUT, mix/enable). Neutral defaults are
 * the identity: [NodeParcel.compileNode] turns a default node into exactly
 * the compiled identity the shader's loop applies as a no-op.
 *
 * The fields ride the SCHEMA RAW (wheel units, DI pivot default, hue degrees,
 * temp/tint -1..1, lutMix 0..1): the CPU/gpu compile lives in
 * [com.ritim.ritimcraw.engine.NodeParcel] (F1-5's wire), not here.
 */
data class GradeNodeV2(
    val enabled: Boolean = true,
    val lift: NodeWheelV2 = NodeWheelV2(),
    val gamma: NodeWheelV2 = NodeWheelV2(),
    val gain: NodeWheelV2 = NodeWheelV2(),
    val offset: NodeWheelV2 = NodeWheelV2(),
    val contrast: Float = 1.0f,
    val pivot: Float = NodeParcelGlueness.DI_MID_GRAY,
    val sat: Float = 1.0f,
    val hue: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val curveY: NodeCurveV2 = NodeCurveV2(),
    val curveR: NodeCurveV2 = NodeCurveV2(),
    val curveG: NodeCurveV2 = NodeCurveV2(),
    val curveB: NodeCurveV2 = NodeCurveV2(),
    val logShadow: NodeWheelV2 = NodeWheelV2(),
    val logMid: NodeWheelV2 = NodeWheelV2(),
    val logHi: NodeWheelV2 = NodeWheelV2(),
    val logLow: Float = NodeParcelGlueness.LOG_LOW,
    val logHigh: Float = NodeParcelGlueness.LOG_HIGH,
    val mix: Float = 1.0f,
    /** The node LUT's library id, or null = no node LUT; resolved per job like stage 24. */
    val lutId: String? = null,
    /** The LUT result's mix into the node's DI value (rawedit GradeNode.lutMix). */
    val lutMix: Float = 1.0f,
    /**
     * F1-8a: the node's display name (free text, at most [NodeListOps.NAME_MAX] characters), or null = the
     * strip's default "Node N". OPTIONAL in the JSON: an absent key reads as null and a null name writes NO
     * key, so every file written before this field stays byte-identical on a round trip and the schema
     * version does not change (owner decision 2026-11-24).
     */
    val name: String? = null,
)

/**
 * The output transform (plan §3.1 `output`): legacy = v1's exact tonemap+LUT
 * TAIL; opendrt = the OpenDRT spec's user parameters. A v2 record carries
 * ONE mode; the migration only ever writes legacy (K3).
 */
data class OutputTransformV2(
    /** "legacy" (default) | "opendrt" — matches rawedit::NodesOutput + OpenDrt. */
    val mode: String = MODE_LEGACY,
    /** The legacy branch; ignored while mode is opendrt. */
    val legacy: LegacyOutputV2 = LegacyOutputV2(),
    /** The OpenDRT branch's user parameters; ignored while mode is legacy. */
    val opendrt: OpenDrtParamsV2 = OpenDrtParamsV2(),
) {
    companion object {
        const val MODE_LEGACY = "legacy"
        const val MODE_OPENDRT = "opendrt"

        internal fun parse(m: Map<*, *>): OutputTransformV2 {
            val mode = when (val s = m["mode"] as? String) {
                MODE_OPENDRT -> MODE_OPENDRT
                else -> MODE_LEGACY // absent/unknown = the honest fallback (a
                // record this build cannot interpret must not gain a DRT look)
            }
            val legacy = m["legacy"] as? Map<*, *>
            val drt = m["opendrt"] as? Map<*, *>
            return OutputTransformV2(
                mode = mode,
                legacy = LegacyOutputV2.parse(legacy),
                opendrt = OpenDrtParamsV2.parse(drt),
            )
        }
    }
}

/** The legacy output branch: v1's tonemap + managed/stage-24 LUT fields. */
data class LegacyOutputV2(
    val tonemap: Int = 0,
    val lutId: String? = null,
    val lutStrength: Float = 100f,
    val lutInterp: LutInterpK? = null,
) {
    companion object {
        internal fun parse(m: Map<*, *>?): LegacyOutputV2 =
            if (m == null) LegacyOutputV2() else LegacyOutputV2(
                tonemap = (m["tonemap"] as? Double)?.toInt() ?: 0,
                lutId = m["lutId"] as? String,
                lutStrength = (m["lutStrength"] as? Double)?.toFloat() ?: 100f,
                lutInterp = LutInterpK.parse(m["lutInterp"]),
            )
    }
}

/**
 * The OpenDRT user parameters (plan §4.2; rawedit::OpenDrtSpec ordinals):
 * look, tonescale, cwp are the enum ordinATES the C++ spec consumes, grey is
 * the mid-grey nit (3..25), display picks the EOTF (0 sRGB 2.2 / 1 Rec.1886 /
 * 2 HLG 1000).
 */
data class OpenDrtParamsV2(
    val look: Int = 0,
    val tonescale: Int = 0,
    val cwp: Int = 0,
    val cwpLimit: Float = 0.25f,
    val grey: Float = 10f,
    val display: Int = 0,
) {
    companion object {
        internal fun parse(m: Map<*, *>?): OpenDrtParamsV2 =
            if (m == null) OpenDrtParamsV2() else OpenDrtParamsV2(
                look = (m["look"] as? Double)?.toInt() ?: 0,
                tonescale = (m["tonescale"] as? Double)?.toInt() ?: 0,
                cwp = (m["cwp"] as? Double)?.toInt() ?: 0,
                cwpLimit = (m["cwpLimit"] as? Double)?.toFloat() ?: 0.25f,
                grey = (m["grey"] as? Double)?.toFloat() ?: 10f,
                display = (m["display"] as? Double)?.toInt() ?: 0,
            )
    }
}

/** The stabilization group (K10, plan §7): folds v1's five flat values + engine. */
data class StabV2(
    /** 0 gyro (the pre-image-engine value AND no-key meaning), 1 image. */
    val engine: Int = 0,
    /** 0 Smooth, 1 Lock First Frame, 2 Horizon Lock. */
    val mode: Int = 0,
    /** Smoothing percent 0..100. */
    val strength: Float = 50f,
    /** Max auto-crop zoom 1.0..1.5. */
    val zoomLimit: Float = 1.25f,
    /** Manual sync trim ms. */
    val trimMs: Float = 0f,
    /** 35mm-equivalent focal override; 0 = the container's value. */
    val focalMm: Float = 0f,
)

/** The transform group: crop + straighten + the folded stab. */
data class TransformV2(
    val cropAspect: Int = 0,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropW: Float = 1f,
    val cropH: Float = 1f,
    val straightenDeg: Float = 0f,
    /** Stabilization OFF = no group at all (the omit-when-off rule). */
    val stab: StabV2? = null,
) {
    companion object {
        internal fun parse(m: Any?): TransformV2 {
            if (m !is Map<*, *>) return TransformV2()
            val stabM = m["stab"] as? Map<*, *>
            return TransformV2(
                cropAspect = (m["cropAspect"] as? Double)?.toInt() ?: 0,
                cropX = (m["cropX"] as? Double)?.toFloat() ?: 0f,
                cropY = (m["cropY"] as? Double)?.toFloat() ?: 0f,
                cropW = (m["cropW"] as? Double)?.toFloat() ?: 1f,
                cropH = (m["cropH"] as? Double)?.toFloat() ?: 1f,
                straightenDeg = (m["straightenDeg"] as? Double)?.toFloat() ?: 0f,
                stab = stabM?.let {
                    StabV2(
                        engine = (it["engine"] as? Double)?.toInt() ?: 0,
                        mode = (it["mode"] as? Double)?.toInt() ?: 0,
                        strength = (it["strength"] as? Double)?.toFloat() ?: 50f,
                        zoomLimit = (it["zoomLimit"] as? Double)?.toFloat() ?: 1.25f,
                        trimMs = (it["trimMs"] as? Double)?.toFloat() ?: 0f,
                        focalMm = (it["focalMm"] as? Double)?.toFloat() ?: 0f,
                    )
                },
            )
        }
    }
}

/**
 * The schema-2 clip grade. The wire record of a per-clip grade, a preset's
 * grade and an export snapshot — one renderer ([render]), one parser
 * ([parse]), mirroring the v1 fiber's single-schema discipline.
 */
data class ClipGradeV2(
    val version: Int = SCHEMA_VERSION,
    val develop: DevelopV2 = DevelopV2(),
    val legacy: LegacyControlsV2 = LegacyControlsV2(),
    val nodes: List<GradeNodeV2> = emptyList(),
    val output: OutputTransformV2 = OutputTransformV2(),
    val transform: TransformV2 = TransformV2(),
) {
    companion object {
        /** The schema-2 marker (the record-level `v2` key; the file's own top-level version is ProjectMigrations.CURRENT_VERSION). */
        const val SCHEMA_VERSION = 2

        /**
         * Parses one v2 record with v1's per-field tolerance: a missing group
         * falls back to its neutral defaults, and a field this build cannot
         * read (unknown type) resolves as its default rather than crashing
         * the whole record (the migration's row-tolerant carry rule).
         */
        fun parse(m: Map<*, *>): ClipGradeV2 = ClipGradeV2(
            version = (m["v2"] as? Double)?.toInt() ?: SCHEMA_VERSION,
            develop = DevelopV2.parse(m["develop"]),
            legacy = LegacyControlsV2.parse(m["legacy"]),
            nodes = parseNodes(m["nodes"]),
            output = OutputTransformV2.parse(m["output"] as? Map<*, *> ?: emptyMap<String, Any?>()),
            transform = TransformV2.parse(m["transform"]),
        )

        private fun parseNodes(v: Any?): List<GradeNodeV2> {
            val arr = v as? List<*> ?: return emptyList()
            val out = ArrayList<GradeNodeV2>(arr.size)
            for (item in arr) {
                val m = item as? Map<*, *> ?: continue
                out.add(
                    GradeNodeV2(
                        enabled = m["enabled"] as? Boolean ?: true,
                        lift = parseWheel(m["lift"]),
                        gamma = parseWheel(m["gamma"]),
                        gain = parseWheel(m["gain"]),
                        offset = parseWheel(m["offset"]),
                        contrast = (m["contrast"] as? Double)?.toFloat() ?: 1.0f,
                        pivot = (m["pivot"] as? Double)?.toFloat() ?: NodeParcelGlueness.DI_MID_GRAY,
                        sat = (m["sat"] as? Double)?.toFloat() ?: 1.0f,
                        hue = (m["hue"] as? Double)?.toFloat() ?: 0f,
                        temp = (m["temp"] as? Double)?.toFloat() ?: 0f,
                        tint = (m["tint"] as? Double)?.toFloat() ?: 0f,
                        curveY = NodeCurveV2.parse(m["curveY"]) ?: NodeCurveV2(),
                        curveR = NodeCurveV2.parse(m["curveR"]) ?: NodeCurveV2(),
                        curveG = NodeCurveV2.parse(m["curveG"]) ?: NodeCurveV2(),
                        curveB = NodeCurveV2.parse(m["curveB"]) ?: NodeCurveV2(),
                        logShadow = parseWheel(m["logShadow"]),
                        logMid = parseWheel(m["logMid"]),
                        logHi = parseWheel(m["logHi"]),
                        logLow = (m["logLow"] as? Double)?.toFloat() ?: NodeParcelGlueness.LOG_LOW,
                        logHigh = (m["logHigh"] as? Double)?.toFloat() ?: NodeParcelGlueness.LOG_HIGH,
                        mix = (m["mix"] as? Double)?.toFloat() ?: 1.0f,
                        lutId = m["lutId"] as? String,
                        lutMix = (m["lutMix"] as? Double)?.toFloat() ?: 1.0f,
                        name = NodeListOps.normalizeName(m["name"] as? String),
                    ),
                )
            }
            return out
        }

        private fun parseWheel(v: Any?): NodeWheelV2 {
            val m = v as? Map<*, *> ?: return NodeWheelV2()
            return NodeWheelV2(
                x = (m["x"] as? Double)?.toFloat() ?: 0f,
                y = (m["y"] as? Double)?.toFloat() ?: 0f,
                master = (m["master"] as? Double)?.toFloat() ?: 0f,
            )
        }
    }
}

/** The develop group: v1's RAW/denoise/sharpen controls. */
data class DevelopV2(
    val exposureEv: Float = 0f,
    val tempK: Float = 5600f,
    val tint: Float = 0f,
    val asShot: Boolean = true,
    val highlightReconstruction: Boolean = true,
    val keepVignetting: Boolean = true,
    val highlightsCurve: Int = com.ritim.ritimcraw.data.HIGHLIGHTS_CURVE_V1,
    val noiseReduction: Float = 0f,
    val chromaDenoise: Float = 0f,
    val temporalNr: Boolean = false,
    val temporalStrength: Float = 0f,
    val sharpness: Float = 0f,
    val detail: Float = 0f,
) {
    companion object {
        internal fun parse(m: Any?): DevelopV2 {
            if (m !is Map<*, *>) return DevelopV2()
            val tempK = (m["tempK"] as? Double)?.toFloat() ?: 5600f
            val tint = (m["tint"] as? Double)?.toFloat() ?: 0f
            return DevelopV2(
                exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                tempK = tempK,
                tint = tint,
                asShot = m["asShot"] as? Boolean ?: (tempK == 5600f && tint == 0f),
                highlightReconstruction = m["highlightReconstruction"] as? Boolean ?: true,
                keepVignetting = m["keepVignetting"] as? Boolean ?: true,
                highlightsCurve = (m["highlightsCurve"] as? Double)?.toInt() ?: com.ritim.ritimcraw.data.HIGHLIGHTS_CURVE_LEGACY,
                noiseReduction = (m["noiseReduction"] as? Double)?.toFloat() ?: 0f,
                chromaDenoise = (m["chromaDenoise"] as? Double)?.toFloat() ?: 0f,
                temporalNr = m["temporalNr"] as? Boolean ?: false,
                temporalStrength = (m["temporalStrength"] as? Double)?.toFloat() ?: 0f,
                sharpness = (m["sharpness"] as? Double)?.toFloat() ?: 0f,
                detail = (m["detail"] as? Double)?.toFloat() ?: 0f,
            )
        }
    }
}

/** The legacy group: v1's stage 9-20 tone/color controls. */
data class LegacyControlsV2(
    val highlights: Float = 0f,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 0f,
    val shadows: Float = 0f,
    val midtones: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val wheelShadowX: Float = 0f,
    val wheelShadowY: Float = 0f,
    val wheelMidX: Float = 0f,
    val wheelMidY: Float = 0f,
    val wheelHighX: Float = 0f,
    val wheelHighY: Float = 0f,
    val splitShadowHue: Float = -1f,
    val splitHighlightHue: Float = -1f,
    val splitBalance: Float = 0f,
) {
    companion object {
        internal fun parse(m: Any?): LegacyControlsV2 {
            if (m !is Map<*, *>) return LegacyControlsV2()
            return LegacyControlsV2(
                highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                blackPoint = (m["blackPoint"] as? Double)?.toFloat() ?: 0f,
                whitePoint = (m["whitePoint"] as? Double)?.toFloat() ?: 0f,
                shadows = (m["shadows"] as? Double)?.toFloat() ?: 0f,
                midtones = (m["midtones"] as? Double)?.toFloat() ?: 0f,
                contrast = (m["contrast"] as? Double)?.toFloat() ?: 0f,
                saturation = (m["saturation"] as? Double)?.toFloat() ?: 0f,
                wheelShadowX = (m["wheelShadowX"] as? Double)?.toFloat() ?: 0f,
                wheelShadowY = (m["wheelShadowY"] as? Double)?.toFloat() ?: 0f,
                wheelMidX = (m["wheelMidX"] as? Double)?.toFloat() ?: 0f,
                wheelMidY = (m["wheelMidY"] as? Double)?.toFloat() ?: 0f,
                wheelHighX = (m["wheelHighX"] as? Double)?.toFloat() ?: 0f,
                wheelHighY = (m["wheelHighY"] as? Double)?.toFloat() ?: 0f,
                splitShadowHue = (m["splitShadowHue"] as? Double)?.toFloat() ?: -1f,
                splitHighlightHue = (m["splitHighlightHue"] as? Double)?.toFloat() ?: -1f,
                splitBalance = (m["splitBalance"] as? Double)?.toFloat() ?: 0f,
            )
        }
    }
}

/**
 * The rendering counterpart: one wire record per v2 grade, byte-stable.
 * Shared by GradeStore's grades fiber, PresetStore's preset rows and
 * ExportStore's snapshot rows — the v1 fiber's "one render, one parse" rule.
 */
object GradeV2Fiber {

    /** Renders one v2 record; group order fixed, byte-stable. */
    fun render(g: ClipGradeV2): String {
        val sb = StringBuilder()
        sb.append("{\"v2\":").append(g.version)
        // develop: every field renders (the v1 flat rules carry over; the
        // group is the unit, `develop` always renders).
        val d = g.develop
        sb.append(",\"develop\":{")
            .append("\"exposureEv\":").append(num(d.exposureEv))
            .append(",\"tempK\":").append(num(d.tempK))
            .append(",\"tint\":").append(num(d.tint))
            .append(",\"asShot\":").append(d.asShot)
            .append(",\"highlightReconstruction\":").append(d.highlightReconstruction)
            .append(",\"keepVignetting\":").append(d.keepVignetting)
            .append(if (d.highlightsCurve != com.ritim.ritimcraw.data.HIGHLIGHTS_CURVE_LEGACY) ",\"highlightsCurve\":${d.highlightsCurve}" else "")
            .append(",\"noiseReduction\":").append(num(d.noiseReduction))
            .append(",\"chromaDenoise\":").append(num(d.chromaDenoise))
            .append(",\"temporalNr\":").append(d.temporalNr)
            .append(",\"temporalStrength\":").append(num(d.temporalStrength))
            .append(",\"sharpness\":").append(num(d.sharpness))
            .append(",\"detail\":").append(num(d.detail))
            .append('}')
        // legacy: every field renders (the legacy block is a whole unit;
        // neutral values are as meaningful as set ones here).
        val l = g.legacy
        sb.append(",\"legacy\":{")
            .append("\"highlights\":").append(num(l.highlights))
            .append(",\"blackPoint\":").append(num(l.blackPoint))
            .append(",\"whitePoint\":").append(num(l.whitePoint))
            .append(",\"shadows\":").append(num(l.shadows))
            .append(",\"midtones\":").append(num(l.midtones))
            .append(",\"contrast\":").append(num(l.contrast))
            .append(",\"saturation\":").append(num(l.saturation))
            .append(",\"wheelShadowX\":").append(num(l.wheelShadowX))
            .append(",\"wheelShadowY\":").append(num(l.wheelShadowY))
            .append(",\"wheelMidX\":").append(num(l.wheelMidX))
            .append(",\"wheelMidY\":").append(num(l.wheelMidY))
            .append(",\"wheelHighX\":").append(num(l.wheelHighX))
            .append(",\"wheelHighY\":").append(num(l.wheelHighY))
            .append(",\"splitShadowHue\":").append(num(l.splitShadowHue))
            .append(",\"splitHighlightHue\":").append(num(l.splitHighlightHue))
            .append(",\"splitBalance\":").append(num(l.splitBalance))
            .append('}')
        // nodes: always renders (even empty — `nodes:[]` is the fast-path
        // marker; §3.2 "nodes=[] renders nothing but the empty list").
        sb.append(",\"nodes\":[")
        g.nodes.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            renderNode(sb, n)
        }
        sb.append(']')
        // output
        sb.append(",\"output\":{\"mode\":").append(ProjectJson.quote(g.output.mode))
        sb.append(",\"legacy\":{\"tonemap\":").append(g.output.legacy.tonemap)
            .append(",\"lutId\":")
            .append(if (g.output.legacy.lutId == null) "null" else ProjectJson.quote(g.output.legacy.lutId))
            .append(",\"lutStrength\":").append(num(g.output.legacy.lutStrength))
        if (g.output.legacy.lutInterp != null) {
            sb.append(",\"lutInterp\":").append(g.output.legacy.lutInterp.render())
        }
        sb.append('}')
        val drt = g.output.opendrt
        sb.append(",\"opendrt\":{\"look\":").append(drt.look)
            .append(",\"tonescale\":").append(drt.tonescale)
            .append(",\"cwp\":").append(drt.cwp)
            .append(",\"cwpLimit\":").append(num(drt.cwpLimit))
            .append(",\"grey\":").append(num(drt.grey))
            .append(",\"display\":").append(drt.display)
            .append("}}")
        // transform: crop renders omit-when-neutral (v1's rule — a full-rect
        // FREE crop with 0 straighten writes no keys); stab renders
        // omit-when-off with ALL sub values when on (K10/§7: engine explicit).
        val t = g.transform
        sb.append(",\"transform\":{")
        var keyCount = 0
        if (t.cropAspect != 0 || t.cropX != 0f || t.cropY != 0f || t.cropW != 1f ||
            t.cropH != 1f || t.straightenDeg != 0f
        ) {
            sb.append("\"cropAspect\":").append(t.cropAspect)
                .append(",\"cropX\":").append(num(t.cropX))
                .append(",\"cropY\":").append(num(t.cropY))
                .append(",\"cropW\":").append(num(t.cropW))
                .append(",\"cropH\":").append(num(t.cropH))
                .append(",\"straightenDeg\":").append(num(t.straightenDeg))
            keyCount = 6
        }
        if (t.stab != null) {
            if (keyCount > 0) sb.append(',')
            sb.append("\"stab\":{\"engine\":").append(t.stab.engine)
                .append(",\"mode\":").append(t.stab.mode)
                .append(",\"strength\":").append(num(t.stab.strength))
                .append(",\"zoomLimit\":").append(num(t.stab.zoomLimit))
                .append(",\"trimMs\":").append(num(t.stab.trimMs))
                .append(",\"focalMm\":").append(num(t.stab.focalMm))
                .append('}')
        }
        sb.append('}')
        sb.append('}')
        return sb.toString()
    }

    private fun renderNode(sb: StringBuilder, n: GradeNodeV2) {
        sb.append('{')
        sb.append("\"enabled\":").append(n.enabled)
        renderWheel(sb, "lift", n.lift, first = false)
        renderWheel(sb, "gamma", n.gamma, first = false)
        renderWheel(sb, "gain", n.gain, first = false)
        renderWheel(sb, "offset", n.offset, first = false)
        sb.append(",\"contrast\":").append(num(n.contrast))
            .append(",\"pivot\":").append(num(n.pivot))
            .append(",\"sat\":").append(num(n.sat))
            .append(",\"hue\":").append(num(n.hue))
            .append(",\"temp\":").append(num(n.temp))
            .append(",\"tint\":").append(num(n.tint))
        sb.append(",\"curveY\":")
        NodeCurveV2.render(sb, n.curveY)
        sb.append(",\"curveR\":")
        NodeCurveV2.render(sb, n.curveR)
        sb.append(",\"curveG\":")
        NodeCurveV2.render(sb, n.curveG)
        sb.append(",\"curveB\":")
        NodeCurveV2.render(sb, n.curveB)
        renderWheel(sb, "logShadow", n.logShadow, first = false)
        renderWheel(sb, "logMid", n.logMid, first = false)
        renderWheel(sb, "logHi", n.logHi, first = false)
        sb.append(",\"logLow\":").append(num(n.logLow))
            .append(",\"logHigh\":").append(num(n.logHigh))
            .append(",\"mix\":").append(num(n.mix))
            .append(",\"lutId\":")
            .append(if (n.lutId == null) "null" else ProjectJson.quote(n.lutId))
            .append(",\"lutMix\":").append(num(n.lutMix))
        if (n.name != null) sb.append(",\"name\":").append(ProjectJson.quote(n.name))
        sb.append('}')
    }

    private fun renderWheel(sb: StringBuilder, name: String, w: NodeWheelV2, first: Boolean) {
        if (!first) sb.append(',')
        sb.append('"').append(name).append("\":{\"x\":").append(num(w.x))
            .append(",\"y\":").append(num(w.y))
            .append(",\"master\":").append(num(w.master))
            .append('}')
    }
}

/** The shared constants the v2 schema carries from the C++ core (NodeParcel mirrors them; asserted by NodeParcelTest). */
internal object NodeParcelGlueness {
    /** rawedit kDiMidGray — the node pivot default. */
    const val DI_MID_GRAY = 0.336043272f

    /** rawedit kNodeLogLow/kNodeLogHigh — the log-wheel zone limits. */
    const val LOG_LOW = 0.142f
    const val LOG_HIGH = 0.552f
}

/**
 * The GradeK view of one v2 record (K2's adapter): the model every current
 * renderer, warp resolver and parcel encoder already consumes, rebuilt from
 * the v2 groups so a migrated project's existing renders keep their exact
 * pixels. The v1 parser and this adapter produce the SAME GradeK for the
 * SAME v1 data — the bit-exactness proof (JVM test: v1 parse vs migrate +
 * adapter, GradeK equals and GradeParcel.encode equals).
 *
 * NOT REACHED: `usage` semantics this build cannot honour (a grade that
 * declares `mode: opendrt` or carries nodes) — [toGradeK] returns null there,
 * the honest refusal: F1-6's binding wires those, not a conversion that
 * would silently drop them.
 */
fun ClipGradeV2.toGradeK(): GradeK? {
    if (output.mode != OutputTransformV2.MODE_LEGACY) return null
    if (nodes.isNotEmpty()) return null
    return toGradeKLenient()
}

/**
 * The develop + legacy-tone fields of this record as a [GradeK], IGNORING the node chain and a non-legacy output
 * (the strict [toGradeK] refuses those, which made the editor read a clip that has nodes as neutral: its slider
 * values vanished and the next edit wrote the neutral values back). The nodes/output travel separately
 * (GradeStore.getV2, putPreservingChain); this is only what the legacy sliders show.
 */
fun ClipGradeV2.toGradeKLenient(): GradeK {
    val t = transform
    return GradeK(
        version = GradeK.SCHEMA_VERSION,
        exposureEv = develop.exposureEv,
        highlights = legacy.highlights,
        tempK = develop.tempK,
        tint = develop.tint,
        saturation = legacy.saturation,
        blackPoint = legacy.blackPoint,
        whitePoint = legacy.whitePoint,
        shadows = legacy.shadows,
        midtones = legacy.midtones,
        contrast = legacy.contrast,
        asShot = develop.asShot,
        highlightReconstruction = develop.highlightReconstruction,
        keepVignetting = develop.keepVignetting,
        highlightsCurve = develop.highlightsCurve,
        sharpness = develop.sharpness,
        detail = develop.detail,
        tonemap = output.legacy.tonemap,
        lutId = output.legacy.lutId,
        lutStrength = output.legacy.lutStrength,
        noiseReduction = develop.noiseReduction,
        chromaDenoise = develop.chromaDenoise,
        temporalNr = develop.temporalNr,
        temporalStrength = develop.temporalStrength,
        stabilization = t.stab != null,
        stabilizationMode = t.stab?.mode ?: 0,
        stabilizationStrength = t.stab?.strength ?: 50f,
        stabilizationZoomLimit = t.stab?.zoomLimit ?: 1.25f,
        stabilizationTrimMs = t.stab?.trimMs ?: 0f,
        stabilizationFocalMm = t.stab?.focalMm ?: 0f,
        stabilizationEngine = t.stab?.engine ?: 0,
        cropAspect = t.cropAspect,
        cropX = t.cropX,
        cropY = t.cropY,
        cropW = t.cropW,
        cropH = t.cropH,
        straightenDeg = t.straightenDeg,
        wheelShadowX = legacy.wheelShadowX,
        wheelShadowY = legacy.wheelShadowY,
        wheelMidX = legacy.wheelMidX,
        wheelMidY = legacy.wheelMidY,
        wheelHighX = legacy.wheelHighX,
        wheelHighY = legacy.wheelHighY,
        splitShadowHue = legacy.splitShadowHue,
        splitHighlightHue = legacy.splitHighlightHue,
        splitBalance = legacy.splitBalance,
        lutInterp = output.legacy.lutInterp,
    )
}

/**
 * The forward direction: a v1 [GradeK] to its v2 twin, used by the migration
 * step via the raw record (see [GradeV2Migration.grade1ToV2Record]) and by
 * tests. Every field carries number-for-number; `stable` has no v1 source so
 * it renders null (off) unless the grade says otherwise.
 */
fun GradeK.toClipGradeV2(): ClipGradeV2 = ClipGradeV2(
    version = ClipGradeV2.SCHEMA_VERSION,
    develop = DevelopV2(
        exposureEv = exposureEv,
        tempK = tempK,
        tint = tint,
        asShot = asShot,
        highlightReconstruction = highlightReconstruction,
        keepVignetting = keepVignetting,
        highlightsCurve = highlightsCurve,
        noiseReduction = noiseReduction,
        chromaDenoise = chromaDenoise,
        temporalNr = temporalNr,
        temporalStrength = temporalStrength,
        sharpness = sharpness,
        detail = detail,
    ),
    legacy = LegacyControlsV2(
        highlights = highlights,
        blackPoint = blackPoint,
        whitePoint = whitePoint,
        shadows = shadows,
        midtones = midtones,
        contrast = contrast,
        saturation = saturation,
        wheelShadowX = wheelShadowX,
        wheelShadowY = wheelShadowY,
        wheelMidX = wheelMidX,
        wheelMidY = wheelMidY,
        wheelHighX = wheelHighX,
        wheelHighY = wheelHighY,
        splitShadowHue = splitShadowHue,
        splitHighlightHue = splitHighlightHue,
        splitBalance = splitBalance,
    ),
    nodes = emptyList(),
    output = OutputTransformV2(
        mode = OutputTransformV2.MODE_LEGACY,
        legacy = LegacyOutputV2(
            tonemap = tonemap,
            lutId = lutId,
            lutStrength = lutStrength,
            lutInterp = lutInterp,
        ),
    ),
    transform = TransformV2(
        cropAspect = cropAspect,
        cropX = cropX,
        cropY = cropY,
        cropW = cropW,
        cropH = cropH,
        straightenDeg = straightenDeg,
        stab = if (stabilization) {
            StabV2(
                engine = stabilizationEngine,
                mode = stabilizationMode,
                strength = stabilizationStrength,
                zoomLimit = stabilizationZoomLimit,
                trimMs = stabilizationTrimMs,
                focalMm = stabilizationFocalMm,
            )
        } else {
            null
        },
    ),
)
