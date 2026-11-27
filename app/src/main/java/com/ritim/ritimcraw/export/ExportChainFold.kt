package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.NodeChainWire

/**
 * (G4/E2-b) The export-side chain fold: the snapshot's v2 chain wire,
 * RE-TARGETED to the export's own output transform. PURE: the same wire
 * in gives the same wire out; nothing here reads a device or a grade
 * store.
 *
 * WHY AN OVERRIDE EXISTS: the wire's output block carries the grade's
 * DISPLAY ordinal — what the EDITOR previewed (sRGB 2.2 / Rec.1886).
 * An HLG export's job is a DIFFERENT output transform (HLG 1000-nit
 * code values): the plan resolved HLG, so the develop session's DRT
 * must run with display = 2 (OpenDrtDisplay::Hlg1000), or the "HLG
 * file" would carry SDR-display code values with HLG container tags —
 * a lie. The CPU door already rides this through jniExportFrameParcel's
 * chain->drtDisplay; the GPU door rides the SAME slot through the
 * wire's own output block (NodeChainWire.SLOT_OUT_DISPLAY).
 *
 * THE HONEST RULES:
 *  - The override applies ONLY when [forceHlg] is true (the plan
 *    resolved HLG); an SDR export's chain travels UNCHANGED (today's
 *    behavior, byte-identical).
 *  - A NULL wire stays null (the legacy fast path; HLG from a v1 grade
 *    is the plan's own TenBit/color tags — nothing to override).
 *  - A wire whose outputMode is NOT OpenDRT (a plain NODES chain) is
 *    returned UNCHANGED: the display slot is IGNORED for outputMode 0
 *    (the wire's own layout contract), so touching it would be a lie.
 *  - The copy never re-encodes or re-validates the wire (the encoder
 *    already refused anything invalid; [NodeChainWire.encode] owns the
 *    length contract).
 */
object ExportChainFold {

    /** OpenDrtDisplay ordinals (core/include/rawedit/OpenDrt.h): 2 = Hlg1000. */
    const val DISPLAY_HLG_1000 = 2

    /**
     * [chainWire] with its output DISPLAY ordinal set to HLG 1000 when
     * [forceHlg] is true and the wire carries an OpenDRT output; the
     * SAME instance otherwise (no copy, no change).
     */
    fun forHlg(chainWire: FloatArray?, forceHlg: Boolean): FloatArray? {
        if (chainWire == null || !forceHlg) return chainWire
        // The wire's own layout: [1] outputMode, the output block's last
        // slot the display ordinal (NodeChainWire.SLOT_OUT_DISPLAY).
        if (chainWire.size < NodeChainWire.OUTPUT || chainWire[1] != 1.0f) return chainWire
        val o = chainWire.size - NodeChainWire.OUTPUT + NodeChainWire.SLOT_OUT_DISPLAY
        if (chainWire[o] == DISPLAY_HLG_1000.toFloat()) return chainWire
        val copy = chainWire.copyOf()
        copy[o] = DISPLAY_HLG_1000.toFloat()
        return copy
    }
}
