package com.ritim.ritimcraw.data.grade2

/**
 * F1-8a: the node strip's list operations as PURE functions over `ClipGradeV2.nodes` (docs/F1_8_COLOR_UI_DESIGN.md
 * section 31): add, duplicate, delete, enable/disable, reset, move. The UI calls these and hands the result to
 * the existing grade store / history; nothing here knows about Compose, the preview or the schema's JSON.
 *
 * Contract: every function returns a NEW list; an operation that is not allowed (the 8-node limit, an index out
 * of range) returns the SAME list instance, so a caller can detect "nothing happened" with `===` and show the
 * reason (see [addBlockedReason]). The limit mirrors rawedit::kMaxNodes (core/include/rawedit/ColorModel.h).
 */
object NodeListOps {
    /** rawedit::kMaxNodes. */
    const val MAX_NODES = 8

    fun canAdd(nodes: List<GradeNodeV2>): Boolean = nodes.size < MAX_NODES

    /** Why "+" is hidden/disabled: the limit, or null when adding is allowed (the strip shows the reason, design section 21). */
    fun addBlockedReason(nodes: List<GradeNodeV2>): String? =
        if (canAdd(nodes)) null else "limit"

    /** Appends a neutral (identity) node. */
    fun add(nodes: List<GradeNodeV2>): List<GradeNodeV2> =
        if (!canAdd(nodes)) nodes else nodes + GradeNodeV2()

    /** Inserts a copy of node [index] right after it (the copy keeps every parameter and the LUT id). */
    fun duplicate(nodes: List<GradeNodeV2>, index: Int): List<GradeNodeV2> {
        if (index !in nodes.indices || !canAdd(nodes)) return nodes
        return nodes.toMutableList().also { it.add(index + 1, nodes[index]) }
    }

    fun delete(nodes: List<GradeNodeV2>, index: Int): List<GradeNodeV2> {
        if (index !in nodes.indices) return nodes
        return nodes.toMutableList().also { it.removeAt(index) }
    }

    /** Flips the node's enable flag (a disabled node stays in the list and the wire, the shader skips it). */
    fun toggleEnabled(nodes: List<GradeNodeV2>, index: Int): List<GradeNodeV2> {
        if (index !in nodes.indices) return nodes
        return nodes.toMutableList().also { it[index] = nodes[index].copy(enabled = !nodes[index].enabled) }
    }

    /** Back to the identity node, keeping its enable flag (a reset must not silently re-enable a disabled node). */
    fun reset(nodes: List<GradeNodeV2>, index: Int): List<GradeNodeV2> {
        if (index !in nodes.indices) return nodes
        return nodes.toMutableList().also { it[index] = GradeNodeV2(enabled = nodes[index].enabled) }
    }

    /** Moves node [from] to position [to] (both in range); nodes apply in list order, so this changes the look. */
    fun move(nodes: List<GradeNodeV2>, from: Int, to: Int): List<GradeNodeV2> {
        if (from !in nodes.indices || to !in nodes.indices || from == to) return nodes
        return nodes.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** The selection after an operation: stays in range; after a delete it moves to the previous node (or 0). */
    fun selectionAfterDelete(deleted: Int, newSize: Int): Int =
        if (newSize <= 0) -1 else (deleted - 1).coerceIn(0, newSize - 1)
}
