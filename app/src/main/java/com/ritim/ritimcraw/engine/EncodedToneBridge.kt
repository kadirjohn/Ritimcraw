package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2

/**
 * Owner decision 2026-11-25 (step 4(b)): on an ENCODED clip the Highlights and Shadows sliders drive the node
 * chain's LOG highlight / log shadow wheels instead of the legacy tone-band stages 9-20, which stay off for
 * encoded clips (the encoded CPU oracle and its GPU door both skip them, so the sliders used to do nothing there:
 * measured on a Mi-Log 4K60 and an 8K clip, frame luma identical at highlights -50). The user sees the same
 * slider; MCRAW is untouched (this runs in [EncodedSource] only).
 *
 * Mechanism: a synthetic node is APPENDED to the clip's node chain WIRE ([NodeChainWire] layout: header 3,
 * 34 floats per node, the curve block, the 6-slot output tail). Its logHi.master = highlights/100 and
 * logShadow.master = shadows/100 (the wheel master reaches the log curve as master * 0.5, core/src/ColorModel.cpp),
 * everything else neutral. The same wire goes to BOTH the CPU oracle and the GPU door, so preview, export and
 * the parity contract all see one chain. It is appended LAST (the tone shaping acts after the user's own nodes);
 * it has no curves, so the curve block and the tail are untouched. A chain already at the N1 cap of 8 nodes has no
 * room: the wire is returned unchanged (the sliders then do nothing, honestly, instead of dropping a user node).
 */
internal object EncodedToneBridge {

    /**
     * The synthetic node's region thresholds in DI space (DI mid grey = 0.5). The node default (0.142 / 0.552) puts the
     * highlight region above about +0.9 stop, and ordinary SDR-range footage sits barely above that: measured on a
     * Mi-Log 4K60 and an 8K frame in the app, Highlights -100 changed no pixel by more than 3 levels. Narrowing the
     * band around mid grey makes the two sliders act on the whole upper / lower half of an ordinary exposure.
     */
    internal const val SYN_LOG_LOW = 0.35f
    internal const val SYN_LOG_HIGH = 0.45f

    /** Slider percent (-100..100) to the wheel master the log curve reads. */
    internal fun wheelMaster(percent: Float): Float = percent.coerceIn(-100f, 100f) / 100f

    /**
     * [wire] = the clip's node chain wire (null = no chain); returns the wire the develop should use. Same instance
     * when both sliders are 0 (so the legacy fast path stays the fast path) or when the chain is full.
     */
    fun apply(highlights: Float, shadows: Float, wire: FloatArray?): FloatArray? {
        if (highlights == 0f && shadows == 0f) return wire
        val base = wire ?: NodeChainWire.encode(ClipGradeV2(), emptyList())
        val n = base[0].toInt()
        if (n >= NodeChainWire.MAX_NODES) return wire
        val record = NodeChainWire.encode(
            ClipGradeV2(),
            listOf(
                GradeNodeV2(
                    logHi = NodeWheelV2(0f, 0f, wheelMaster(highlights)),
                    logShadow = NodeWheelV2(0f, 0f, wheelMaster(shadows)),
                    logLow = SYN_LOG_LOW,
                    logHigh = SYN_LOG_HIGH,
                ),
            ),
        ).copyOfRange(NodeChainWire.HEADER, NodeChainWire.HEADER + NodeChainWire.STRIDE)
        val at = NodeChainWire.HEADER + n * NodeChainWire.STRIDE
        val out = FloatArray(base.size + NodeChainWire.STRIDE)
        System.arraycopy(base, 0, out, 0, at)
        System.arraycopy(record, 0, out, at, NodeChainWire.STRIDE)
        System.arraycopy(base, at, out, at + NodeChainWire.STRIDE, base.size - at)
        out[0] = (n + 1).toFloat()
        return out
    }
}
