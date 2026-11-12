package com.ritim.ritimcraw.ui.editor

/**
 * THE SCOPE'S PRESENTATION TRI-STATE (owner request 2026-11-12, §2): the
 * scope strip's open flag becomes a three-way choice —
 *
 *  [OFF]   no scope at all (the pre-existing closed state);
 *  [STRIP] the full strip below the transport (the pre-existing open
 *          state: Histogram | Waveform selector, mode rows, the graph,
 *          the label band);
 *  [MINI]  the strip collapsed to a small display-only overlay pinned
 *          to a preview corner — tapping the GRAPH itself in the strip
 *          shrinks it there, so the preview/transport/panel space the
 *          strip occupied is reclaimed while the scope stays visible.
 *          Tapping the mini expands the full strip again.
 *
 * The corner toggle stays the third way in/out: it opens the STRIP
 * from OFF, and from STRIP or MINI it closes to OFF — one button, one
 * meaning ("show the scope / hide the scope"), no hidden third
 * behavior on a two-state icon.
 *
 * EVERY transition is a pure function here (JVM-tested like PanelSheet
 * and SliderTiers): the screen only stores the enum and calls these —
 * no transition logic lives in the composable.
 *
 * PERSISTENCE: the state is rememberSaveable like the strip's other
 * choices (content, modes, sampling) and is NOT keyed to the clip —
 * the scope presentation survives a clip switch the same way the
 * content choice does (the strip's precedent; the RESULTS are keyed to
 * the clip, the presentation is not). The MINI overlay therefore
 * re-appears over the next clip with that clip's own computed data —
 * never a stale frame drawn over a new clip.
 */
enum class ScopePresentation {
    OFF,
    STRIP,
    MINI,
}

/**
 * The pure transition rules for [ScopePresentation] — the scope's
 * state machine, JVM-tested in ScopePresentationTest.
 */
internal object ScopePresentationRules {

    /** Tapping the graph inside the OPEN strip: shrink to the mini. */
    fun onGraphTap(state: ScopePresentation): ScopePresentation = when (state) {
        ScopePresentation.STRIP -> ScopePresentation.MINI
        // A graph tap can only arrive while the strip is composed —
        // but the rule stays total: OFF and MINI keep their state
        // (OFF has no graph; MINI's own tap expands, see onMiniTap).
        ScopePresentation.OFF, ScopePresentation.MINI -> state
    }

    /** Tapping the mini overlay: expand the full strip again. */
    fun onMiniTap(state: ScopePresentation): ScopePresentation = when (state) {
        ScopePresentation.MINI -> ScopePresentation.STRIP
        // Total for the same reason: outside MINI the rule is identity.
        ScopePresentation.OFF, ScopePresentation.STRIP -> state
    }

    /**
     * The corner toggle: the two-state show/hide button. OFF -> STRIP
     * (the scope's entry point); STRIP -> OFF and MINI -> OFF (the
     * button means "hide the scope", whatever shape it is in — a mini
     * hiding as a mini would leave the user hunting for how to close
     * it, and a toggle that cycles OFF -> STRIP -> MINI -> OFF hides
     * its third state behind a two-state icon).
     */
    fun onCornerToggle(state: ScopePresentation): ScopePresentation = when (state) {
        ScopePresentation.OFF -> ScopePresentation.STRIP
        ScopePresentation.STRIP -> ScopePresentation.OFF
        ScopePresentation.MINI -> ScopePresentation.OFF
    }

    /** The strip's own close button: closes the scope entirely. */
    fun onClose(state: ScopePresentation): ScopePresentation = ScopePresentation.OFF
}
