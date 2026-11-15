package com.ritim.ritimcraw.engine.mask

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.RotationState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * THE REGIONAL-GRADE MASK MODEL — the pure, GPU/UI-independent decision
 * layer for per-region grade adjustments (night research 2026-11-16, the
 * B2 package's first deliverable — NEW FILE: no shader, no editor, no
 * shared-file edits; the shader/parcel/UI wiring is the integrator's).
 *
 * HOME: `engine/mask/` — the mask domain gets its own package beside the
 * render engine it will plug into, so the later GPU/UI pieces join it
 * without crowding the engine root.
 *
 * WHAT THIS IS. A clip carries a [MaskState]: an ordered list of [MaskK]
 * entries. Each mask is an analytic shape ([MaskShape.Radial] ellipse or
 * [MaskShape.Linear] gradient — no lasso/freeform in this deliverable)
 * whose parameters live in SOURCE-NORMALIZED coordinates (fractions
 * 0..1 of the unrotated source frame — the CropState.RectF convention),
 * a feather 0..1, an invert flag, an enabled flag, and a partial grade
 * delta ([MaskGradeDelta] — the allowed regional knob set). Evaluation
 * is pure scalar math: given a point in source-normalized coordinates,
 * [MaskState.deltaAt] returns the blended regional delta at that point
 * and [MaskState.coverageAt] the combined coverage weight. The GPU does
 * the per-pixel work later; this model is the reference the shader must
 * reproduce and the scalar seam the UI hit-tests against. Everything
 * here is JVM-pure (no Android imports) — fully host-testable.
 *
 * THE NEUTRAL IDENTITY (the acceptance's "empty/off = NEUTRAL"): an
 * empty list, an all-disabled list, or a list whose enabled masks all
 * carry the neutral delta leaves the grade UNTOUCHED — [deltaAt] is the
 * neutral delta at every point, so [MaskGradeDelta.applyTo] passes the
 * grade through with factor 1 everywhere (tested). The coverage union of
 * nothing is 0 (no region is masked) — both facts are stated and
 * tested.
 *
 * THE BLEND RULE (chosen: ADDITIVE per knob, UNION for coverage):
 *   deltaAt(p)    = sum over enabled non-neutral masks of w_i(p) * delta_i
 *   coverageAt(p) = 1 - product over enabled masks of (1 - w_i(p))
 * (a neutral-delta mask contributes nothing to the delta sum but still
 * draws coverage — the user's shape-in-progress.)
 * The list order is STABLE for identity/undo/serialization, but the
 * blend is commutative by construction: reordering masks never changes
 * the render (tested). Why additive over ordered-overwrite: stacked
 * masks compose predictably (two +0.5 exposure masks = +1.0 where they
 * overlap), the render is reorder-independent (drag-to-reorder is
 * cosmetic — no re-render), and the GPU accumulates in one pass. The
 * cost: a mask cannot CUT another mask's delta — the cut primitive is
 * invert (an inverted mask applies its delta to the complement region).
 * Ordered-overwrite (later masks replace earlier where their weight is
 * above 0) was rejected: order-dependent renders plus a confused mental
 * model for stacked adjustments. Where masks stack a knob past its
 * range, the blend CLAMPS to the knob's range (the same clamp the
 * editor's sliders enforce) — [MaskState.deltaAt] always returns a
 * valid delta.
 *
 * THE ALLOWED KNOB SET (six floats, all neutral at 0 — see
 * [MaskGradeDelta] for the ranges): exposureEv, highlights, shadows,
 * saturation, tint, tempShiftK. Why exactly these: they are the
 * per-pixel POINTWISE tone/color knobs — each has a native pointwise
 * develop stage (14/15/11/20/17/16), so a spatial weight modulates
 * them with no neighborhood concerns, and they are offset-like
 * (0 = neutral) so the additive blend and the neutral identity hold by
 * construction. Temperature travels as a SHIFT in kelvin, not an
 * absolute tempK: the regional correction composes with the global
 * white balance (nudge the sky warm RELATIVE to the global setting)
 * and keeps 0 = neutral — an absolute tempK would make the neutral
 * ambiguous (5600) and would ignore the global value it sits on top of.
 * Excluded, deliberately: black/white point, midtones, contrast
 * (scene-referred stages 9/10/12/13 that run BEFORE exposure —
 * regional versions interact with the global exposure stage
 * non-commutatively, and curve-shape knobs do not blend as offsets);
 * sharpness/detail/denoise (spatial kernels — a mask edge inside a
 * kernel needs different math, out of scope for the analytic model);
 * tonemap/LUT/highlight-reconstruction/vignette/stabilization
 * (per-frame or structural, not per-pixel look deltas); color wheel +
 * split tone (pointwise but vector/hue knobs — appendable later without
 * breaking the format, kept out of the first flat six for wire
 * simplicity).
 *
 * STAGE-INSERTION PROPOSAL (the integrator's single decision — this
 * model implements nothing in the pipeline):
 * RECOMMENDED: the mask delta modulates the grade PER-PIXEL inside the
 * existing pointwise tone/color chain (CPU stages 9..20; the GPU POST
 * pass), after the global color adjustments' own contributions and
 * BEFORE sharpening/detail (21/22), tonemap (23) and the gyro warp. In
 * GPU terms: the POST pass computes the mask weight per pixel
 * ([MaskK.weightAt] is the reference), blends each knob as
 * global + w * delta ([MaskGradeDelta.applyTo] is the scalar
 * reference), and runs the pointwise stages with that spatial grade;
 * the unsharp passes, TAIL and warp keep the global grade.
 * Why there:
 * + every allowed knob has its native stage inside 9..20 — a spatial
 *   grade preserves each knob's native semantics AND order (a regional
 *   exposure still feeds the later highlights/saturation stages,
 *   exactly as a global one does);
 * + sharpening/detail run on the FINAL masked image — the user
 *   sharpens what they see;
 * + tonemap and LUT stay global — a regional exposure is not
 *   re-tonemapped per region (predictable, one curve);
 * + the warp stays after grading: masks are authored and evaluated in
 *   SOURCE space, so they move with the content — no per-frame mask
 *   warp.
 * TRADEOFFS / the alternative: a SINGLE new stage after 20 (one
 * mask-blend pass applying the deltas as post-adjustments) is cheaper
 * to wire — one insertion point, no per-stage changes — but changes
 * semantics: a regional exposure applied after saturation never feeds
 * it, and the deltas behave as flat post-filters rather than grades.
 * If the integrator picks that, the math in this file still holds
 * ([deltaAt] is the per-point delta; only WHERE it applies to the
 * chain changes).
 *
 * PARCEL VS SEPARATE UNIFORMS (the integrator decides; this model
 * proposes): GradeParcel is a 29-slot flat FloatArray, append-only,
 * GENERATED. Option (a) APPEND the masks to the parcel: roughly 14
 * floats per mask x [MAX_MASKS] = 8 plus a count is over a hundred new
 * slots — the flat contract balloons, every native call ships all
 * masks even when unused, and the generator (single-writer file)
 * churns on every mask-model change. Option (b) SEPARATE WIRE
 * (proposed): masks travel beside the parcel as their own uniform
 * array or small buffer (the GPU consumes [MaskK] fields directly);
 * the parcel stays 29 slots, the mask count can grow without touching
 * the parcel contract, and the mask model owns its own wire. Cost: one
 * extra JNI argument or setter at the bridge.
 *
 * CACHE KEY: [signature] — a deterministic compact string over the
 * masks that RENDER (enabled AND non-neutral delta), sorted, so it is
 * order-independent: equal signatures mean equal mask-field renders.
 * The empty/neutral list signs "" and MUST compare equal to a
 * mask-free grade — untouched clips keep their exact old cache
 * behavior. The integrator appends it to the render identity at the
 * cached-CFA seam (RenderRequest / NativeSource's cached path): same
 * frame + same grade + same mask signature means reuse; any mask edit
 * means re-develop. Renaming a mask's id does NOT change the signature
 * (ids have no render effect).
 *
 * SAVEABLE: [renderJson]/[parseJson] double as the rememberSaveable
 * payload — one representation for disk (project.json), Bundle and
 * undo snapshots; the model is value-equal after the round-trip
 * (tested), which is the snapshot/preset/undo contract.
 */
data class MaskState(val masks: List<MaskK> = emptyList()) {

    init {
        require(masks.size <= MAX_MASKS) {
            "a clip carries at most $MAX_MASKS masks, got ${masks.size}"
        }
        val seen = HashSet<String>()
        for (m in masks) {
            require(seen.add(m.id)) { "duplicate mask id \"${m.id}\"" }
        }
    }

    /** True when the list leaves the render untouched (the neutral). */
    val isNeutralForRender: Boolean
        get() = effectiveMasks.isEmpty()

    /**
     * The masks that actually render: enabled AND carrying a non-neutral
     * delta. This is the subset the GPU uploads and the signature signs;
     * disabled or neutral-delta masks have no render effect by definition.
     */
    val effectiveMasks: List<MaskK>
        get() = masks.filter { it.enabled && !it.delta.isNeutral }

    /**
     * The combined COVERAGE weight at a source-normalized point — the
     * union blend 1 - (1-w0)(1-w1)... over enabled masks (the alpha
     * standard; each mask adds coverage evidence). 0 for the empty list.
     * This is the overlay-drawing / hit-testing scalar, NOT the grade
     * modulation (that is [deltaAt]).
     */
    fun coverageAt(x: Float, y: Float): Float {
        var keep = 1f
        for (m in masks) {
            if (!m.enabled) continue
            val w = m.weightAt(x, y)
            if (w <= 0f) continue
            keep *= 1f - w
            if (keep <= 0f) return 1f
        }
        return 1f - keep
    }

    /**
     * The blended regional grade DELTA at a source-normalized point:
     * the additive per-knob sum of every ENABLED, NON-NEUTRAL mask's
     * weight times its delta, CLAMPED to the knob ranges where masks
     * stack. The neutral delta (all knobs 0) when nothing renders
     * here — the identity that leaves the grade unchanged. The GPU
     * reproduces this per pixel; this is the scalar reference.
     */
    fun deltaAt(x: Float, y: Float): MaskGradeDelta {
        var ex = 0f
        var hl = 0f
        var sh = 0f
        var sa = 0f
        var ti = 0f
        var tp = 0f
        for (m in masks) {
            if (!m.enabled || m.delta.isNeutral) continue
            val w = m.weightAt(x, y)
            if (w <= 0f) continue
            val d = m.delta
            ex += w * d.exposureEv
            hl += w * d.highlights
            sh += w * d.shadows
            sa += w * d.saturation
            ti += w * d.tint
            tp += w * d.tempShiftK
        }
        return MaskGradeDelta(
            exposureEv = ex.coerceIn(MaskGradeDelta.EXPOSURE_EV_MIN, MaskGradeDelta.EXPOSURE_EV_MAX),
            highlights = hl.coerceIn(MaskGradeDelta.UNIT_MIN, MaskGradeDelta.UNIT_MAX),
            shadows = sh.coerceIn(MaskGradeDelta.UNIT_MIN, MaskGradeDelta.UNIT_MAX),
            saturation = sa.coerceIn(MaskGradeDelta.UNIT_MIN, MaskGradeDelta.UNIT_MAX),
            tint = ti.coerceIn(MaskGradeDelta.UNIT_MIN, MaskGradeDelta.UNIT_MAX),
            tempShiftK = tp.coerceIn(-MaskGradeDelta.TEMP_SHIFT_MAX_K, MaskGradeDelta.TEMP_SHIFT_MAX_K),
        )
    }

    /**
     * The coverage weights over a small source-normalized grid —
     * row-major, `out[y * xSteps + x]`, each cell sampled at its CENTER
     * ((x + 0.5) / xSteps, (y + 0.5) / ySteps). Scalar-first convenience
     * for tests and a future CPU overlay preview; the GPU does the
     * per-pixel work.
     */
    fun coverageGrid(xSteps: Int, ySteps: Int): FloatArray {
        require(xSteps > 0 && ySteps > 0) {
            "grid steps must be positive, got ${xSteps}x$ySteps"
        }
        val out = FloatArray(xSteps * ySteps)
        var i = 0
        for (y in 0 until ySteps) {
            val py = (y + 0.5f) / ySteps
            for (x in 0 until xSteps) {
                out[i++] = coverageAt((x + 0.5f) / xSteps, py)
            }
        }
        return out
    }

    /**
     * The render cache-key component (see the class KDoc's CACHE KEY
     * section): a deterministic compact string over the RENDERING
     * masks (enabled AND non-neutral delta), sorted so it is
     * order-independent — equal signatures mean equal mask-field
     * renders, and the neutral list signs "". Duplicate masks are NOT
     * deduped (two identical masks stack by design).
     */
    fun signature(): String {
        val parts = ArrayList<String>(masks.size)
        for (m in masks) {
            if (!m.enabled || m.delta.isNeutral) continue
            val sb = StringBuilder(48)
            val d = m.delta
            when (val sh = m.shape) {
                is MaskShape.Radial -> sb.append('r')
                    .append(keyFloat(sh.cx)).append(',')
                    .append(keyFloat(sh.cy)).append(',')
                    .append(keyFloat(sh.rx)).append(',')
                    .append(keyFloat(sh.ry)).append(',')
                    .append(keyFloat(sh.angleDeg))
                is MaskShape.Linear -> sb.append('l')
                    .append(keyFloat(sh.x0)).append(',')
                    .append(keyFloat(sh.y0)).append(',')
                    .append(keyFloat(sh.x1)).append(',')
                    .append(keyFloat(sh.y1))
            }
            sb.append(',').append(keyFloat(m.feather))
            sb.append(',').append(if (m.invert) '1' else '0')
            sb.append(',').append(keyFloat(d.exposureEv))
            sb.append(',').append(keyFloat(d.highlights))
            sb.append(',').append(keyFloat(d.shadows))
            sb.append(',').append(keyFloat(d.saturation))
            sb.append(',').append(keyFloat(d.tint))
            sb.append(',').append(keyFloat(d.tempShiftK))
            parts.add(sb.toString())
        }
        if (parts.isEmpty()) return ""
        parts.sort()
        return parts.joinToString(";")
    }

    /**
     * The persistence wire: a JSON array of mask objects, hand-rolled
     * in the GradeStore/SourceRepository style — compact StringBuilder
     * rendering, omit-when-default fields (angle 0, feather 0, invert
     * false, enabled true, each neutral knob) so a typical mask stays
     * small, fixed key order, no external JSON dependency. The empty
     * list renders "[]" — the WRITER (integrator) omits the whole masks
     * key when the list is empty, so files this build writes for an
     * untouched clip stay byte-identical to the pre-mask format.
     */
    fun renderJson(): String {
        val sb = StringBuilder()
        sb.append('[')
        masks.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(quoteJson(m.id))
            when (val sh = m.shape) {
                is MaskShape.Radial -> {
                    sb.append(",\"shape\":\"radial\"")
                        .append(",\"cx\":").append(numJson(sh.cx))
                        .append(",\"cy\":").append(numJson(sh.cy))
                        .append(",\"rx\":").append(numJson(sh.rx))
                        .append(",\"ry\":").append(numJson(sh.ry))
                    if (sh.angleDeg != 0f) {
                        sb.append(",\"angle\":").append(numJson(sh.angleDeg))
                    }
                }
                is MaskShape.Linear -> sb
                    .append(",\"shape\":\"linear\"")
                    .append(",\"x0\":").append(numJson(sh.x0))
                    .append(",\"y0\":").append(numJson(sh.y0))
                    .append(",\"x1\":").append(numJson(sh.x1))
                    .append(",\"y1\":").append(numJson(sh.y1))
            }
            if (m.feather != 0f) sb.append(",\"feather\":").append(numJson(m.feather))
            if (m.invert) sb.append(",\"invert\":true")
            if (!m.enabled) sb.append(",\"enabled\":false")
            val d = m.delta
            if (d.exposureEv != 0f) sb.append(",\"exposureEv\":").append(numJson(d.exposureEv))
            if (d.highlights != 0f) sb.append(",\"highlights\":").append(numJson(d.highlights))
            if (d.shadows != 0f) sb.append(",\"shadows\":").append(numJson(d.shadows))
            if (d.saturation != 0f) sb.append(",\"saturation\":").append(numJson(d.saturation))
            if (d.tint != 0f) sb.append(",\"tint\":").append(numJson(d.tint))
            if (d.tempShiftK != 0f) sb.append(",\"tempShiftK\":").append(numJson(d.tempShiftK))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    companion object {
        /**
         * The mask-count cap. 8 keeps the first GPU integration inside a
         * comfortable fragment-uniform budget (about 14 floats per mask =
         * ~28 vec4s plus a count, far under any ES 3.0 limit) while
         * matching how many regions a clip realistically needs; the JSON
         * is append-only, so raising it later is format-compatible.
         */
        const val MAX_MASKS = 8

        /**
         * Parses the JSON array [text] back into a [MaskState]. The
         * tolerant-reader rule (the GradeStore style): structural JSON
         * errors throw IllegalArgumentException, but a mask ENTRY that is
         * invalid (unknown shape from a future build, out-of-range
         * value, missing required param) is SKIPPED, never crashing the
         * load — and the list truncates to [MAX_MASKS]. Value-equal with
         * what [renderJson] wrote (tested).
         */
        fun parseJson(text: String): MaskState {
            val p = MaskJsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is List<*>) throw IllegalArgumentException("masks root is not an array")
            val out = ArrayList<MaskK>(root.size)
            val seen = HashSet<String>()
            for (v in root) {
                if (out.size >= MAX_MASKS) break
                val m = v as? Map<*, *> ?: continue
                val mask = parseMask(m) ?: continue
                // A duplicate id in a corrupt/hand-edited file: keep the
                // FIRST entry (the writer never emits duplicates), skip the
                // rest — the load never crashes on a bad list.
                if (!seen.add(mask.id)) continue
                out.add(mask)
            }
            return MaskState(out)
        }

        private fun parseMask(m: Map<*, *>): MaskK? {
            val id = m["id"] as? String ?: return null
            if (id.isBlank()) return null
            return try {
                val shape: MaskShape = when (m["shape"] as? String) {
                    "radial" -> MaskShape.Radial(
                        cx = (m["cx"] as? Double)?.toFloat() ?: return null,
                        cy = (m["cy"] as? Double)?.toFloat() ?: return null,
                        rx = (m["rx"] as? Double)?.toFloat() ?: return null,
                        ry = (m["ry"] as? Double)?.toFloat() ?: return null,
                        angleDeg = (m["angle"] as? Double)?.toFloat() ?: 0f,
                    )
                    "linear" -> MaskShape.Linear(
                        x0 = (m["x0"] as? Double)?.toFloat() ?: return null,
                        y0 = (m["y0"] as? Double)?.toFloat() ?: return null,
                        x1 = (m["x1"] as? Double)?.toFloat() ?: return null,
                        y1 = (m["y1"] as? Double)?.toFloat() ?: return null,
                    )
                    // A shape this build does not know (a future build's
                    // entry): skip, do not crash the load.
                    else -> return null
                }
                MaskK(
                    id = id,
                    shape = shape,
                    feather = (m["feather"] as? Double)?.toFloat() ?: 0f,
                    invert = m["invert"] as? Boolean ?: false,
                    enabled = m["enabled"] as? Boolean ?: true,
                    delta = MaskGradeDelta(
                        exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                        highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                        shadows = (m["shadows"] as? Double)?.toFloat() ?: 0f,
                        saturation = (m["saturation"] as? Double)?.toFloat() ?: 0f,
                        tint = (m["tint"] as? Double)?.toFloat() ?: 0f,
                        tempShiftK = (m["tempShiftK"] as? Double)?.toFloat() ?: 0f,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                // An out-of-range value in a corrupt/hand-edited file:
                // skip the entry, keep the rest of the list.
                null
            }
        }
    }
}

/**
 * ONE mask: an analytic shape in source-normalized coordinates, a
 * feather, an invert flag, an enabled flag, and the regional grade
 * delta it carries. Constructed values are validated (finite, in
 * range, non-degenerate) — the UI can never build an invalid mask;
 * the parser skips invalid entries instead of constructing them.
 * The id is caller-minted UI identity (list keys, undo targets); it
 * must be non-blank and unique within the clip's [MaskState] and has
 * NO render effect (it is not part of the cache signature).
 */
data class MaskK(
    val id: String,
    val shape: MaskShape,
    /** Edge softness 0..1 (0 = hard edge; 1 = maximally soft). */
    val feather: Float = 0f,
    /** True = apply the delta to the shape's COMPLEMENT (weight 1-w). */
    val invert: Boolean = false,
    /** False = the mask renders nothing (skipped entirely). */
    val enabled: Boolean = true,
    /** The regional grade delta this mask carries. */
    val delta: MaskGradeDelta = MaskGradeDelta(),
) {

    init {
        require(id.isNotBlank()) { "mask id must not be blank" }
        require(feather.isFinite()) { "mask feather must be finite, got $feather" }
        require(feather in 0f..1f) {
            "mask feather must be 0..1, got $feather"
        }
    }

    /**
     * This mask's weight at a source-normalized point, 0..1: the
     * shape's ramp (1 inside, 0 outside) softened by [feather], then
     * inverted when [invert]. The `enabled` flag does NOT gate this —
     * the mask's own weight is meaningful for the UI even while
     * disabled (the shape still draws, grayed); the STATE-level
     * functions ([MaskState.coverageAt], [MaskState.deltaAt]) skip
     * disabled masks. The GPU must reproduce this exactly; the
     * smoothstep here is the reference.
     */
    fun weightAt(x: Float, y: Float): Float {
        val r = shape.rampAt(x, y)
        // The feather band: RADIAL softens inward from the boundary
        // (q: 0 center, 1 boundary -> band [1-feather, 1]); LINEAR
        // centers the ramp on the axis midpoint (t: 0 start line,
        // 1 end line -> band [0.5-feather/2, 0.5+feather/2]). Feather 0
        // = a hard cut (the boundary belongs to the INSIDE, weight 1);
        // feather 1 = the maximally soft ramp.
        val e0: Float
        val e1: Float
        when (shape) {
            is MaskShape.Radial -> {
                e0 = 1f - feather
                e1 = 1f
            }
            is MaskShape.Linear -> {
                e0 = 0.5f - feather * 0.5f
                e1 = 0.5f + feather * 0.5f
            }
        }
        val w = 1f - smoothstep(e0, e1, r)
        return if (invert) 1f - w else w
    }
}

/**
 * The analytic mask shapes (the research's set: radial ellipse + linear
 * gradient; NO lasso/freeform in this deliverable). Parameters are
 * SOURCE-NORMALIZED fractions 0..1 (the CropState.RectF convention) —
 * centers/anchors MAY sit outside 0..1 (a corner vignette needs an
 * off-frame center); sizes must be positive. Each shape exposes its
 * ramp coordinate at a point: RADIAL q (0 at the center, 1 at the
 * ellipse boundary), LINEAR t (0 on the start line, 1 on the end
 * line, extrapolating linearly beyond). [MaskK.weightAt] turns the
 * ramp into a weight.
 */
sealed class MaskShape {

    /** The raw ramp coordinate at a source-normalized point. */
    abstract fun rampAt(x: Float, y: Float): Float

    /**
     * A radial ellipse: center (cx, cy), radii rx/ry as fractions of
     * the source width/height, and a tilt [angleDeg] (CW-positive, the
     * CropState straighten convention; any finite angle, normalized by
     * the trig). Radii may exceed 1 (a huge soft mask covering the
     * frame) but must be positive.
     */
    data class Radial(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val angleDeg: Float = 0f,
    ) : MaskShape() {

        init {
            require(cx.isFinite() && cy.isFinite() &&
                rx.isFinite() && ry.isFinite() && angleDeg.isFinite()) {
                "radial mask parameters must be finite " +
                    "(cx=$cx cy=$cy rx=$rx ry=$ry angle=$angleDeg)"
            }
            require(rx > 0f && ry > 0f) {
                "radial mask radii must be positive, got rx=$rx ry=$ry"
            }
        }

        override fun rampAt(x: Float, y: Float): Float {
            val dx = x - cx
            val dy = y - cy
            val rad = angleDeg * DEG_TO_RAD
            val c = cos(rad)
            val s = sin(rad)
            // Rotate the offset into the ellipse's own frame
            // (CW-positive tilt: R(-angle) * d in a y-down space).
            val u = dx * c + dy * s
            val v = -dx * s + dy * c
            val nx = u / rx
            val ny = v / ry
            return sqrt(nx * nx + ny * ny)
        }
    }

    /**
     * A linear gradient: weight 1 on the start line through (x0, y0),
     * 0 on the end line through (x1, y1) — the sky/ground mask. The
     * two points define the axis; the ramp is its perpendicular
     * projection, so the mask is a band across the whole frame. The
     * axis must be non-degenerate (start differs from end).
     */
    data class Linear(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) : MaskShape() {

        init {
            require(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite()) {
                "linear mask parameters must be finite " +
                    "(x0=$x0 y0=$y0 x1=$x1 y1=$y1)"
            }
            require(x0 != x1 || y0 != y1) {
                "linear mask needs a non-degenerate axis " +
                    "(start ($x0,$y0) equals end ($x1,$y1))"
            }
        }

        override fun rampAt(x: Float, y: Float): Float {
            val sx = x1 - x0
            val sy = y1 - y0
            val len2 = sx * sx + sy * sy
            return ((x - x0) * sx + (y - y0) * sy) / len2
        }
    }
}

/**
 * The per-mask grade DELTA — the allowed regional knob set (six
 * floats, all neutral at 0; see the MaskState KDoc for why exactly
 * these and why temp is a shift). Ranges mirror the editor's slider
 * ranges for the offset knobs; the temp shift range is the model's
 * own (see [TEMP_SHIFT_MAX_K]). [applyTo] is the scalar reference for
 * how a delta at weight w composes onto a global [GradeK] — the
 * shader must reproduce it; the stage wiring is the integrator's.
 */
data class MaskGradeDelta(
    /** Exposure delta in EV, -5..5 (the exposure slider's range). */
    val exposureEv: Float = 0f,
    /** Highlights delta, -100..100. */
    val highlights: Float = 0f,
    /** Shadows delta, -100..100. */
    val shadows: Float = 0f,
    /** Saturation delta, -100..100. */
    val saturation: Float = 0f,
    /** Tint delta (green-magenta), -100..100. */
    val tint: Float = 0f,
    /** Temperature SHIFT in kelvin, -5000..5000 (see TEMP_SHIFT_MAX_K). */
    val tempShiftK: Float = 0f,
) {

    init {
        require(exposureEv.isFinite() && highlights.isFinite() && shadows.isFinite() &&
            saturation.isFinite() && tint.isFinite() && tempShiftK.isFinite()) {
            "mask delta knobs must be finite " +
                "(exposure=$exposureEv highlights=$highlights shadows=$shadows " +
                "saturation=$saturation tint=$tint tempShift=$tempShiftK)"
        }
        require(exposureEv in EXPOSURE_EV_MIN..EXPOSURE_EV_MAX) {
            "mask exposure delta must be $EXPOSURE_EV_MIN..$EXPOSURE_EV_MAX, got $exposureEv"
        }
        require(highlights in UNIT_MIN..UNIT_MAX) {
            "mask highlights delta must be $UNIT_MIN..$UNIT_MAX, got $highlights"
        }
        require(shadows in UNIT_MIN..UNIT_MAX) {
            "mask shadows delta must be $UNIT_MIN..$UNIT_MAX, got $shadows"
        }
        require(saturation in UNIT_MIN..UNIT_MAX) {
            "mask saturation delta must be $UNIT_MIN..$UNIT_MAX, got $saturation"
        }
        require(tint in UNIT_MIN..UNIT_MAX) {
            "mask tint delta must be $UNIT_MIN..$UNIT_MAX, got $tint"
        }
        require(tempShiftK in -TEMP_SHIFT_MAX_K..TEMP_SHIFT_MAX_K) {
            "mask temp shift must be -$TEMP_SHIFT_MAX_K..$TEMP_SHIFT_MAX_K, got $tempShiftK"
        }
    }

    /** True when every knob is 0 — the delta that changes nothing. */
    val isNeutral: Boolean
        get() = exposureEv == 0f && highlights == 0f && shadows == 0f &&
            saturation == 0f && tint == 0f && tempShiftK == 0f

    /**
     * The scalar reference for applying this delta onto a global grade
     * at weight [weight] (0..1): each knob composes as
     * global + weight * delta, clamped to the editor's slider ranges
     * (and the core's 2000..50000 K temp domain). The neutral delta
     * returns the grade UNCHANGED at any weight — the identity proof.
     * The stage ORDER these knobs apply in is the integrator's single
     * decision (see the MaskState KDoc's proposal); this function is
     * the per-point math, not the pipeline wiring.
     */
    fun applyTo(grade: GradeK, weight: Float = 1f): GradeK {
        // The exact identity: a neutral delta NEVER touches the grade —
        // not even the clamps below, so a grade whose own values sit
        // outside the ranges (an old/hand-edited file) passes through
        // byte-identical rather than being silently clamped.
        if (isNeutral || weight == 0f) return grade
        return grade.copy(
            exposureEv = (grade.exposureEv + exposureEv * weight)
                .coerceIn(EXPOSURE_EV_MIN, EXPOSURE_EV_MAX),
            highlights = (grade.highlights + highlights * weight)
                .coerceIn(UNIT_MIN, UNIT_MAX),
            shadows = (grade.shadows + shadows * weight)
                .coerceIn(UNIT_MIN, UNIT_MAX),
            saturation = (grade.saturation + saturation * weight)
                .coerceIn(UNIT_MIN, UNIT_MAX),
            tint = (grade.tint + tint * weight)
                .coerceIn(UNIT_MIN, UNIT_MAX),
            tempK = (grade.tempK + tempShiftK * weight)
                .coerceIn(TEMP_K_MIN, TEMP_K_MAX),
        )
    }

    companion object {
        const val EXPOSURE_EV_MIN = -5f
        const val EXPOSURE_EV_MAX = 5f

        /** The -100..100 percent knobs (highlights/shadows/saturation/tint). */
        const val UNIT_MIN = -100f
        const val UNIT_MAX = 100f

        /**
         * The temp shift cap. The GLOBAL temp slider covers the absolute
         * 2000..50000 K domain; the regional shift is a relative nudge on
         * top of it, and +-5000 K spans any sane regional correction
         * (sunset warm to open-shade cool) with headroom.
         */
        const val TEMP_SHIFT_MAX_K = 5000f

        /** The core's white-balance domain (the temp slider's range). */
        const val TEMP_K_MIN = 2000f
        const val TEMP_K_MAX = 50000f
    }
}

/**
 * The source <-> display coordinate contract — PURE functions the UI
 * implements its gestures against (the ZoomPan.kt:27 and CropState
 * conventions; the UI writes no transform math of its own).
 *
 * Three spaces meet here:
 * - SOURCE-normalized: the mask parameters' space — the unrotated
 *   sensor frame, fractions 0..1 (what the develop chain samples);
 * - EFFECTIVE-upright: the frame turned to the effective orientation
 *   (container + manual delta) — the crop rect's space (the GradeK
 *   cropX..cropH convention) and the shape of the preview box;
 * - DISPLAY box fractions: the video rect on screen, 0..1 — the
 *   CropOverlay / ZoomPan gesture space.
 *
 * The chain display -> source (this transform's [sourceFromDisplay]):
 * 1. invert ZoomPan: c = (b - 0.5 - pan) / zoom + 0.5 (ZoomPan's
 *    b = 0.5 + pan + z * (c - 0.5), pan in box fractions, zoom >= 1);
 * 2. un-crop: e = crop.xy + crop.wh * c (the displayed content IS the
 *    crop; the crop rect lives in EFFECTIVE space);
 * 3. un-turn: source = mapPointFromEffective(e, effectiveDeg) — the
 *    point analog of CropState.mapRect. Pass
 *    RotationState.effectiveDeg(containerDeg, manualDeltaDeg): the
 *    manual delta is part of the effective turn because the preview
 *    box is effective-shaped (the surface's graphicsLayer rotation is
 *    the mechanism; this box space is the contract).
 * [displayFromSource] is the exact inverse (tested as a round-trip
 * pair in both directions).
 *
 * NOT pre-solved (documented omissions, by design): STRAIGHTEN
 * (+-15 deg) is a display-time rotation about the crop center plus
 * the straighten zoom — if the UI needs gesture-exact mapping while
 * straightening, fold it in with CropState.straightenZoom's recipe
 * before the crop step; and the GYRO WARP is per-frame, display-side —
 * masks are source-locked, so during a warped preview the gesture
 * mapping is approximate by design. This transform carries the
 * three inputs the brief names: the crop rect, the (effective =
 * container + manual) rotation, and the zoom/pan.
 */
data class MaskViewTransform(
    /** Display zoom, >= 1 (the UI supplies ZoomPan's >= MIN_ZOOM value). */
    val zoom: Float = 1f,
    /** Pan in box fractions, x and y (the UI clamps to ZoomPan.panBound). */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** The crop rect in EFFECTIVE-upright space (the GradeK convention). */
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropW: Float = 1f,
    val cropH: Float = 1f,
    /** The effective orientation, container + manual delta (quarter turns). */
    val effectiveDeg: Int = 0,
) {

    init {
        require(zoom.isFinite() && panX.isFinite() && panY.isFinite()) {
            "view transform motion must be finite " +
                "(zoom=$zoom panX=$panX panY=$panY)"
        }
        require(zoom > 0f) { "view zoom must be positive, got $zoom" }
        require(cropX.isFinite() && cropY.isFinite() &&
            cropW.isFinite() && cropH.isFinite()) {
            "view crop must be finite (x=$cropX y=$cropY w=$cropW h=$cropH)"
        }
        require(cropW > 0f && cropH > 0f) {
            "view crop size must be positive, got ${cropW}x${
                cropH
            }"
        }
        require(cropX >= 0f && cropY >= 0f && cropX + cropW <= 1f && cropY + cropH <= 1f) {
            "view crop must stay inside the frame, got " +
                "x=$cropX y=$cropY w=$cropW h=$cropH"
        }
    }

    /** A display box point (fractions of the video rect) -> source point. */
    fun sourceFromDisplay(bx: Float, by: Float): MaskPoint {
        // 1. invert ZoomPan (b = 0.5 + pan + z * (c - 0.5)).
        val cx = (bx - 0.5f - panX) / zoom + 0.5f
        val cy = (by - 0.5f - panY) / zoom + 0.5f
        // 2. un-crop into the effective-upright full frame.
        val ex = cropX + cropW * cx
        val ey = cropY + cropH * cy
        // 3. un-turn into source space.
        return mapPointFromEffective(ex, ey, effectiveDeg)
    }

    /** The exact inverse of [sourceFromDisplay]. */
    fun displayFromSource(sx: Float, sy: Float): MaskPoint {
        // 3'. turn the source point up into effective space.
        val e = mapPointFromEffective(sx, sy, -effectiveDeg)
        // 2'. crop: effective fractions -> displayed-content fractions.
        val cx = (e.x - cropX) / cropW
        val cy = (e.y - cropY) / cropH
        // 1'. ZoomPan forward: b = 0.5 + pan + z * (c - 0.5).
        return MaskPoint(
            x = 0.5f + panX + zoom * (cx - 0.5f),
            y = 0.5f + panY + zoom * (cy - 0.5f),
        )
    }

    companion object {
        /**
         * The point analog of CropState.mapRect: maps an EFFECTIVE-
         * upright point into the space rotated the other way by [deg]
         * (inverts a deg-CW quarter turn; 90/180/270/identity — the
         * degenerate-rect cases of mapRect). [deg] may be negative (the
         * inverse mapping uses the opposite turn).
         */
        fun mapPointFromEffective(x: Float, y: Float, deg: Int): MaskPoint =
            when (RotationState.normalize(deg)) {
                90 -> MaskPoint(y, 1f - x)
                180 -> MaskPoint(1f - x, 1f - y)
                270 -> MaskPoint(1f - y, x)
                else -> MaskPoint(x, y)
            }
    }
}

/** A pure x/y pair in some normalized space (no Android graphics types). */
data class MaskPoint(val x: Float, val y: Float)

// ---- shared private helpers ----

private const val DEG_TO_RAD = 0.017453292519943295f

/**
 * The reference smoothstep: 0 below e0, 1 above e1, and
 * u*u*(3-2u) inside (C1-continuous at the band edges). A degenerate
 * band (e1 <= e0) is a hard step with the edge belonging to the
 * INSIDE (x <= e0 -> 0). The GPU must reproduce this exactly.
 */
private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    if (e1 <= e0) return if (x <= e0) 0f else 1f
    val u = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/**
 * Cache-key float rendering: Float.toString is deterministic (shortest
 * round-trip), and -0.0 normalizes to "0" so value-equal floats sign
 * identically.
 */
private fun keyFloat(v: Float): String = if (v == 0f) "0" else v.toString()

/** Kotlin Float.toString always carries a '.' or 'E'; keep as-is. */
private fun numJson(v: Float): String = v.toString()

private fun quoteJson(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Minimal JSON parser (the GradeStore JsonParser style, kept private
 * so this file owns its own wire and no shared-file coupling): objects,
 * arrays, strings, numbers (as Double), booleans, null.
 */
private class MaskJsonParser(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
    }

    fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) throw IllegalArgumentException("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                expect("true")
                true
            }
            'f' -> {
                expect("false")
                false
            }
            'n' -> {
                expect("null")
                null
            }
            else ->
                if (c == '-' || c.isDigit()) parseNumber() else {
                    throw IllegalArgumentException("unexpected char at $i")
                }
        }
    }

    private fun expect(word: String) {
        if (!s.regionMatches(i, word, 0, word.length)) {
            throw IllegalArgumentException("expected $word at $i")
        }
        i += word.length
    }

    private fun parseObject(): LinkedHashMap<String, Any?> {
        i++ // '{'
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return out
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') {
                throw IllegalArgumentException("expected key at $i")
            }
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') {
                throw IllegalArgumentException("expected ':' at $i")
            }
            i++
            out[key] = parseValue()
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == '}' -> {
                    i++
                    return out
                }
                else -> throw IllegalArgumentException("expected ',' or '}' at $i")
            }
        }
    }

    private fun parseArray(): ArrayList<Any?> {
        i++ // '['
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return out
        }
        while (true) {
            out.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> {
                    i++
                    return out
                }
                else -> throw IllegalArgumentException("expected ',' or ']' at $i")
            }
        }
    }

    private fun parseString(): String {
        i++ // '"'
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '"' -> {
                    i++
                    return sb.toString()
                }
                '\\' -> {
                    i++
                    if (i >= s.length) throw IllegalArgumentException("escape at end")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) {
                                throw IllegalArgumentException("bad \\u escape")
                            }
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e at $i")
                    }
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun parseNumber(): Double {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' ||
                s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        val text = s.substring(start, i)
        val v = text.toDoubleOrNull()
            ?: throw IllegalArgumentException("bad number \"$text\" at $start")
        if (v.isInfinite()) throw IllegalArgumentException("number out of range at $start")
        return v
    }
}
