package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2

/**
 * Color model v2's USAGE verdict (F1-6, plan §3.1/§3.2): does one v2 grade
 * ride the node/DRT machinery at all? A LEGACY grade (empty node chain +
 * output mode legacy) takes EXACTLY the pre-F1-6 render paths — the same
 * GradeK adapter, the same 29-slot parcel, the same TAIL — the K2
 * bit-exactness lock. A NODES/DRT grade (any non-neutral node, or the
 * opendrt output) needs the F1-6 binding: the NODES pass between the warp
 * and the TAIL on the GPU, [rawedit.applyNodes] + [rawedit.OpenDrt] on the
 * CPU.
 *
 * Pure: nothing here touches a store, a session or the JNI. The render
 * sites ask, then route — the StabilizationGate.engineFor shape (the
 * verdict, not the policy).
 *
 * THE NEUTRAL-NODE RULE (ColorModel.h GradeNode.isNeutral's own): a node
 * that is disabled, mixed to ~0, or carries all-neutral knobs is a NO-OP
 * the CPU reference skips entirely — so a chain of ONLY such nodes is
 * usage=LEGACY too (the plan's "nodes=[] renders nothing but nodes:[]",
 * applied knob-for-knob). The DRT gate is the output MODE alone: mode
 * opendrt is usage even with an empty chain (the 709 -> DWG -> OpenDRT
 * round trip changes pixels by itself, plan §3.2 stage 7).
 */
object GradeV2Usage {

    /** The render machinery one v2 grade needs. */
    enum class Usage {
        /** Legacy: the v1 code paths, bit-identical (K2). */
        LEGACY,
        /** The node chain and/or the OpenDRT output — F1-6's new doors. */
        NODES,
    }

    fun usage(grade: ClipGradeV2): Usage =
        if (grade.output.mode == OutputTransformV2.MODE_OPENDRT ||
            grade.nodes.any { !it.isNeutralV2() }
        ) {
            Usage.NODES
        } else {
            Usage.LEGACY
        }

    /**
     * The nodes that actually ride the chain: the neutral no-ops STRIPPED
     * (the CPU applyNodes skips them internally; the GPU parcel carries
     * the same trimmed order so both sides run the same surviving nodes —
     * and the nodeCount the parcel crosses is the trimmed count, keeping
     * the N1 cap honest against chains padded with dead nodes).
     */
    fun activeNodes(grade: ClipGradeV2): List<GradeNodeV2> =
        grade.nodes.filter { !it.isNeutralV2() }

    /**
     * ColorModel.h's GradeNode::isNeutral, mirrored knob for knob (the
     * GPU parcel and the CPU reference both skip these; this is the
     * Kotlin-side gate so usage() and activeNodes() agree with core).
     * `!enabled || mix <= 0.0001f` is core's own short-circuit.
     */
    fun GradeNodeV2.isNeutralV2(): Boolean =
        !enabled || mix <= 0.0001f || (
            lift.isNeutralW() && gamma.isNeutralW() && gain.isNeutralW() &&
                offset.isNeutralW() && contrast == 1.0f && sat == 1.0f &&
                hue == 0f && temp == 0f && tint == 0f &&
                curveY.isIdentity() && curveR.isIdentity() &&
                curveG.isIdentity() && curveB.isIdentity() &&
                logShadow.isNeutralW() && logMid.isNeutralW() && logHi.isNeutralW() &&
                lutId == null
        )

    private fun NodeWheelV2.isNeutralW(): Boolean = x == 0f && y == 0f && master == 0f
}
