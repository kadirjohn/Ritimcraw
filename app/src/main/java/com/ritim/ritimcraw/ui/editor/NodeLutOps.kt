package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.GradeNodeV2

/**
 * F1-8d: the node LUT page's pure edits (JVM-tested). A node's LUT is `lutId` (the library's fingerprint id) +
 * `lutMix` (0..1, the LUT's mix into the node's DI value). At most [MAX_LUT_NODES] ENABLED nodes of a chain may carry a
 * live LUT (core's kMaxNodeLuts, the GPU texture budget; validateNodeChain refuses a chain past it), so the page
 * gates a fifth assignment instead of letting the render fail.
 */
object NodeLutOps {
    /** rawedit kMaxNodeLuts. */
    const val MAX_LUT_NODES = 4

    /** core's gate: a LUT counts when its mix is above this. */
    const val LIVE_MIX = 0.0001f

    fun hasLiveLut(node: GradeNodeV2): Boolean = node.lutId != null && node.lutMix > LIVE_MIX

    /** Enabled nodes carrying a live LUT: the count core's validateNodeChain judges. */
    fun liveLutCount(nodes: List<GradeNodeV2>): Int = nodes.count { it.enabled && hasLiveLut(it) }

    /**
     * True when node [index] may take a LUT: it already carries one (changing the choice never adds to the count),
     * or the chain is below the cap, or the node is disabled (a disabled node never counts).
     */
    fun canAssign(nodes: List<GradeNodeV2>, index: Int): Boolean {
        val node = nodes.getOrNull(index) ?: return false
        if (hasLiveLut(node) || !node.enabled) return true
        return liveLutCount(nodes) < MAX_LUT_NODES
    }

    /** Selects [lutId] (null = None). Choosing a cube restores a zero mix to 1 so the choice is visible. */
    fun select(node: GradeNodeV2, lutId: String?): GradeNodeV2 =
        if (lutId == null) node.copy(lutId = null, lutMix = 1f)
        else node.copy(lutId = lutId, lutMix = if (node.lutMix <= LIVE_MIX) 1f else node.lutMix)

    fun withMix(node: GradeNodeV2, mix: Float): GradeNodeV2 = node.copy(lutMix = mix.coerceIn(0f, 1f))

    /** The "Reset LUT" action: no LUT, mix back to 1. */
    fun reset(node: GradeNodeV2): GradeNodeV2 = node.copy(lutId = null, lutMix = 1f)
}
