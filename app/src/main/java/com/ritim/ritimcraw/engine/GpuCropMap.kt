package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import kotlin.math.floor

/**
 * THE GPU-side crop/straighten mapping (A2 GPU crop/straighten preview,
 * owner request 2026-11-23, guide A2) — the pure Kotlin twin of the
 * WINDOW_FRAGMENT crop block (GlesShaderSources.WINDOW_FRAGMENT's
 * uCropOn/uCropRect/uCropOut/uCropZoom/uCropCos/uCropSin branch), and
 * the builder of the crop parcel the render seam carries.
 *
 * SPACE CHAIN (the whole difficulty, per the plan's pitfalls):
 *  - The grade's crop rect is EFFECTIVE-upright (what the user sees).
 *    The GL draw renders CONTAINER-upright pixels (the manual quarter
 *    turn rides PreviewSurface's graphicsLayer OUTSIDE the draw), so
 *    the rect crosses [CropState.mapRect] with the manual delta BEFORE
 *    anything GPU-side sees it — the EXACT PublishCrop/applyToArgb
 *    space mapping, never a new one.
 *  - Inside the draw, the crop map runs on the window rect's local uv
 *    (which covers the CROPPED output), BEFORE the uOrientation fold
 *    composes uv into sensor st; the fold is the shader's own verified
 *    bijection and is reused, not mirrored.
 *  - Sampling is MANUAL bilinear — 4x texelFetch with per-axis index
 *    clamping — because the develop texture is pinned NEAREST (the
 *    Adreno white-window pin, owner evidence 2026-11-15).
 *
 * Three publics, all pure (JVM-testable, no ui imports):
 *  - [requestCrop] builds the JNI parcel: null for the neutral crop
 *    group, else [rectX, rectY, rectW, rectH, straightenDeg] with the
 *    rect mapped through mapRect (CONTAINER-upright fractions).
 *  - [sourcePosition] mirrors applyToArgb's per-pixel inverse map in
 *    output-uv space — an INDEPENDENT implementation (it must NOT
 *    delegate to CropState.cropSourcePosition or the parity test is a
 *    tautology; only the zoom stays single-sourced, via
 *    [CropState.straightenZoom], so the auto-zoom can never disagree).
 *  - [bilinearTaps] mirrors [CropState]'s private sampleArgbBilinear
 *    tap geometry: floor(s - 0.5) clamped per axis, the +1 tap collapsed
 *    at the edge, weights coerced [0, 1].
 *
 * The dims-dependent geometry (outW/outH, the zoom, cos/sin) is owned by
 * the NATIVE twin (app/src/main/cpp/gles/GpuCropMap.h — planCrop),
 * because the develop dims exist only native-side (the JNI bridge's CFA
 * reduction); Kotlin owns only the rect mapRect + the raw angle.
 */
object GpuCropMap {

    /**
     * The all-neutral parcel for the JNI door when [requestCrop] returns
     * null but the seam still needs a non-null FloatArray (the door's
     * cropActive=false carries the values but the shader's uCropOn = 0
     * gate reads none of them): the full rect + 0 deg.
     */
    val NEUTRAL_CROP_PARAMS = floatArrayOf(0f, 0f, 1f, 1f, 0f)

    /**
     * The crop parcel for ONE render request, or null when the crop
     * group is neutral — the SAME inline test [PublishCrop.plan runs][com.ritim.ritimcraw.ui.editor.PublishCrop]
     * (straightenDeg == 0 && CropState.isFull): a neutral crop gets the
     * shader's identity path (uCropOn = 0), never the crop math.
     *
     * The rect crosses [CropState.mapRect] with [manualDeltaDeg] — the
     * manual rotation delta — the EXACT PublishCrop/applyToArgb mapping
     * (the rect is effective-upright fractions of the frame the user
     * sees; PreviewSurface turns the delta via graphicsLayer outside the
     * GL draw, so the draw's pixels are container-upright).
     *
     * The returned parcel is [rectX, rectY, rectW, rectH, straightenDeg]:
     * CONTAINER-upright rect fractions + the RAW angle (the ±15 clamp and
     * the zoom/dims math live native-side, GpuCropMap.h planCrop —
     * Kotlin cannot know this frame's develop dims).
     */
    fun requestCrop(grade: GradeK, manualDeltaDeg: Int): FloatArray? {
        val rect = CropState.RectF(grade.cropX, grade.cropY, grade.cropW, grade.cropH)
        // The PublishCrop.plan inline test, verbatim.
        if (grade.straightenDeg == 0f && CropState.isFull(rect)) return null
        val mapped = CropState.mapRect(rect, manualDeltaDeg)
        return floatArrayOf(mapped.x, mapped.y, mapped.w, mapped.h, grade.straightenDeg)
    }

    /**
     * The output pixel's uv (the window rect's LOCAL normalized
     * coordinate the shader computes as `(gl_FragCoord.xy - uRect.xy) /
     * uRect.zw`): x rightward, y BOTTOM-UP (gl_FragCoord's convention).
     * Test-helper public so the parity suite can drive [sourcePosition]
     * with the shader's own inputs.
     */
    fun outputUv(ox: Int, oy: Int, outW: Int, outH: Int): FloatArray {
        // The destination pixel's rect-local uv: the pixel CENTER in
        // normalized units, y flipped into the shader's bottom-up frame
        // (oy counts rows top-down, the CPU convention).
        val u = (ox + 0.5f) / outW
        val v = 1.0f - (oy + 0.5f) / outH
        return floatArrayOf(u, v)
    }

    /**
     * The WINDOW_FRAGMENT crop map in Kotlin — [sourcePosition] mirrors
     * applyToArgb's per-pixel inverse sampling map (the straighten arm
     * in [CropState.applyToArgb]), written INDEPENDENTLY (no delegation
     * to CropState.cropSourcePosition — the GPU/CPU parity test would be
     * a tautology; only the zoom delegates to
     * [CropState.straightenZoom], single-sourced).
     *
     * Inputs: ([u], [v]) — the output pixel's uv in the window rect's
     * local space (x rightward, y bottom-up, exactly what the shader
     * computes from gl_FragCoord); [r] — the MAPPED crop rect
     * (container-upright fractions); [deg] — the straighten angle;
     * [outW]/[outH] — the cropped output dims; [srcW]/[srcH] — the
     * source (upright) dims. Returns [sx, sy] — the SOURCE pixel
     * position (top-row-first source-pixel coordinates, the space
     * sampleArgbBilinear samples in).
     *
     * The shader lines this mirrors (GlesShaderSources.WINDOW_FRAGMENT,
     * the uCropOn == 1 branch — owner request 2026-11-23, guide A2):
     *   dx = (uv.x - 0.5) * uCropOut.x                      // output px
     *   dy = (0.5 - uv.y) * uCropOut.y                      // y flipped
     *   up  = (src is already upright) uCropRect center * up dims
     *   ox  = (dx * uCropCos + dy * uCropSin) / uCropZoom   // R(-theta)
     *   oy  = (-dx * uCropSin + dy * uCropCos) / uCropZoom
     *   sx  = ctr.x + ox ; sy = ctr.y + oy
     * — verified equal to applyToArgb's dx/dy/inverse map at every
     * output pixel (GpuCropMapTest's grid, 1e-3 px).
     */
    fun sourcePosition(
        u: Float,
        v: Float,
        r: CropState.RectF,
        deg: Float,
        outW: Int,
        outH: Int,
        srcW: Int,
        srcH: Int,
    ): FloatArray {
        // Output px offsets in the CPU convention: dx rightward from the
        // output center, dy DOWNWARD (applyToArgb's own dy) — the uv.y
        // bottom-up axis flips here, the one handedness trap the shader
        // comment pins.
        val dx = (u - 0.5f) * outW
        val dy = (0.5f - v) * outH
        // The auto-zoom — the ONLY delegated value (single-sourced in
        // CropState; the native twin implements the same formula on the
        // upright dims it alone knows).
        val zoom = CropState.straightenZoom(srcW, srcH, r, deg)
        // The rect center in source px.
        val ctrX = (r.x + r.w / 2f) * srcW
        val ctrY = (r.y + r.h / 2f) * srcH
        // Inverse map R(-theta)/zoom — cos/sin from a double theta cast
        // to float (CropState's exact precision; the native twin
        // planCrop computes the SAME pair for the shader's uCropCos/
        // uCropSin uniforms, so GLSL never runs its own trig on the
        // angle).
        val theta = deg.toDouble() * kotlin.math.PI / 180.0
        val c = kotlin.math.cos(theta).toFloat()
        val s = kotlin.math.sin(theta).toFloat()
        val ox = (dx * c + dy * s) / zoom
        val oy = (-dx * s + dy * c) / zoom
        return floatArrayOf(ctrX + ox, ctrY + oy)
    }

    /**
     * The manual bilinear's tap geometry — [CropState]'s private
     * sampleArgbBilinear, mirrored (the indices clamped per axis, the
     * +1 tap collapsed at the frame edge, the weights coerced [0, 1]):
     * the EXACT tap shape the shader's 4x texelFetch block implements
     * (owner request 2026-11-23, guide A2). The CPU clamps texel
     * INDICES, never normalized coordinates — the half-texel st clamp
     * must NOT run on the crop branch.
     */
    fun bilinearTaps(sx: Float, sy: Float, w: Int, h: Int): Taps {
        val x0 = floor((sx - 0.5f).toDouble()).toInt().coerceIn(0, w - 1)
        val y0 = floor((sy - 0.5f).toDouble()).toInt().coerceIn(0, h - 1)
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val fx = (sx - 0.5f - x0).coerceIn(0f, 1f)
        val fy = (sy - 0.5f - y0).coerceIn(0f, 1f)
        return Taps(x0, y0, x1, y1, fx, fy)
    }

    /** The four tap indices + the two fractional weights ([fx], [fy]). */
    data class Taps(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val fx: Float,
        val fy: Float,
    )
}
