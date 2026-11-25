package com.ritim.ritimcraw.ui.editor

import androidx.compose.ui.graphics.Color
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** F1-8b: the node tool-page tabs. Curves joined in F1-8c, the LUT follows in F1-8d; the page order follows the design (wheels first). */
enum class NodeTab { WHEELS, CURVES, ADJUST }

/** The seven node wheels, in the two groups the Wheels page shows. */
enum class NodeWheelId(val primary: Boolean) {
    LIFT(true), GAMMA(true), GAIN(true), OFFSET(true),
    LOG_SHADOW(false), LOG_MID(false), LOG_HI(false);

    companion object {
        val PRIMARY: List<NodeWheelId> = entries.filter { it.primary }
        val LOG: List<NodeWheelId> = entries.filter { !it.primary }
    }
}

/**
 * F1-8b: pure edit logic for the Wheels page (docs/F1_8_COLOR_UI_DESIGN.md section 3, "Tekerlekler"), over
 * `GradeNodeV2`. No Compose, no schema change; every function returns a new node. Ranges mirror the engine:
 * the puck lives in the unit disc (rawedit wheelRgb clamps the magnitude at 1) and the master in -1..1.
 */
object NodeWheelOps {
    /** Puck grid: the same 0.05 the legacy pad stores (SliderSteps.WHEEL_OFFSET_STEP). */
    const val PUCK_STEP = 0.05f

    /** Master grid. */
    const val MASTER_STEP = 0.01f

    fun get(node: GradeNodeV2, id: NodeWheelId): NodeWheelV2 = when (id) {
        NodeWheelId.LIFT -> node.lift
        NodeWheelId.GAMMA -> node.gamma
        NodeWheelId.GAIN -> node.gain
        NodeWheelId.OFFSET -> node.offset
        NodeWheelId.LOG_SHADOW -> node.logShadow
        NodeWheelId.LOG_MID -> node.logMid
        NodeWheelId.LOG_HI -> node.logHi
    }

    fun set(node: GradeNodeV2, id: NodeWheelId, w: NodeWheelV2): GradeNodeV2 = when (id) {
        NodeWheelId.LIFT -> node.copy(lift = w)
        NodeWheelId.GAMMA -> node.copy(gamma = w)
        NodeWheelId.GAIN -> node.copy(gain = w)
        NodeWheelId.OFFSET -> node.copy(offset = w)
        NodeWheelId.LOG_SHADOW -> node.copy(logShadow = w)
        NodeWheelId.LOG_MID -> node.copy(logMid = w)
        NodeWheelId.LOG_HI -> node.copy(logHi = w)
    }

    private fun snap(v: Float, step: Float): Float = (v / step).roundToInt() * step + 0f // + 0f: -0.0 -> 0.0

    /** Puck to (x, y) in the engine's frame (y up): clamped to the unit disc, snapped to [PUCK_STEP]; master untouched. */
    fun setPuck(node: GradeNodeV2, id: NodeWheelId, x: Float, y: Float): GradeNodeV2 {
        var cx = x
        var cy = y
        val r = hypot(cx, cy)
        if (r > 1f) { cx /= r; cy /= r }
        val sx = snap(cx, PUCK_STEP).coerceIn(-1f, 1f)
        val sy = snap(cy, PUCK_STEP).coerceIn(-1f, 1f)
        return set(node, id, get(node, id).copy(x = sx, y = sy))
    }

    fun setMaster(node: GradeNodeV2, id: NodeWheelId, master: Float): GradeNodeV2 =
        set(node, id, get(node, id).copy(master = snap(master.coerceIn(-1f, 1f), MASTER_STEP)))

    /** Pad double-tap: puck to the centre, master kept. */
    fun resetPuck(node: GradeNodeV2, id: NodeWheelId): GradeNodeV2 =
        set(node, id, get(node, id).copy(x = 0f, y = 0f))

    /** Master slider label double-tap: master to 0, puck kept. */
    fun resetMaster(node: GradeNodeV2, id: NodeWheelId): GradeNodeV2 =
        set(node, id, get(node, id).copy(master = 0f))

    /** The reset button: the whole wheel (puck and master). */
    fun resetWheel(node: GradeNodeV2, id: NodeWheelId): GradeNodeV2 = set(node, id, NodeWheelV2())

    fun isNeutral(node: GradeNodeV2, id: NodeWheelId): Boolean = get(node, id).isNeutral()

    /** The pad draws y downwards, the engine's puck has y up: one flip, kept pure so both directions are tested. */
    fun padToWheelY(padY: Float): Float = 0f - padY + 0f
    fun wheelToPadY(wheelY: Float): Float = 0f - wheelY + 0f

    /**
     * The disc colour at a wheel position (engine frame, y up): the rawedit wheelRgb gains (red at 0 degrees,
     * green at 120, blue at 240, magnitude 2/3 * |puck|) mapped around mid grey, so the disc shows what pushing
     * the puck there does. The centre is neutral grey.
     */
    fun discColor(x: Float, y: Float): Color {
        val m = min(1f, hypot(x, y))
        if (m < 1e-6f) return Color(0.5f, 0.5f, 0.5f)
        val th = atan2(y, x)
        val k = m * 2f / 3f
        val third = (2.0 * PI / 3.0).toFloat()
        val r = k * cos(th)
        val g = k * cos(th - third)
        val b = k * cos(th + third)
        return Color(
            (0.5f + 0.75f * r).coerceIn(0f, 1f),
            (0.5f + 0.75f * g).coerceIn(0f, 1f),
            (0.5f + 0.75f * b).coerceIn(0f, 1f),
        )
    }

    /** Pad-frame variant for ColorWheelPad (its y points down). */
    fun padDiscColor(padX: Float, padY: Float): Color = discColor(padX, padToWheelY(padY))

    /** Readout text, e.g. "x +0.25  y -0.10"; locale-independent. */
    fun puckReadout(w: NodeWheelV2): String =
        String.format(java.util.Locale.ROOT, "x %+.2f  y %+.2f", w.x, w.y)

    /** Selection survives a group switch when the wheel is in the new group, else the group's first wheel. */
    fun selectionForGroup(current: NodeWheelId, primary: Boolean): NodeWheelId =
        if (current.primary == primary) current
        else if (primary) NodeWheelId.PRIMARY.first() else NodeWheelId.LOG.first()
}
