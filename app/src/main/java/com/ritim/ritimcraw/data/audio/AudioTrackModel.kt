package com.ritim.ritimcraw.data.audio

/**
 * The audio track model (Stage C, spec AUD-02/AUD-03/AUD-06 and TL-03's
 * audio side) — the audio-specific state the timeline core does NOT
 * model. Pure Kotlin: immutable data classes, no Android imports, so the
 * whole model is JVM-testable headless (AudioTrackOps holds the pure
 * rules; AudioStore persists it into project.json's "audio" section).
 *
 * It COMPLEMENTS the timeline core (data/timeline), it never duplicates
 * it. The split of ownership:
 *   - the TIMELINE CORE owns PLACEMENT: an audio clip is a TimelineClip
 *     on a TrackKind.AUDIO track — its sourceRange/timelineRange are
 *     the project frame grid (the timeline's timebase contract), and
 *     its id is what a video clip's linkedAudioId points at (TL-05);
 *   - THIS model owns the AUDIO-SPECIFIC state: the original-audio link
 *     state per source (AUD-02), gain and fades per clip (AUD-03), and
 *     the SOURCE sample rate (AUD-06) — none of which the timeline
 *     models.
 *
 * PLACEMENT OWNERSHIP BOUNDARY (AUD-03, TL-03 audio side). AudioClip
 * joins the timeline BY ID — AudioClip.id == the audio TimelineClip's
 * id — and deliberately carries NO range and NO track: one placement,
 * one owner. A timeline move, trim or split therefore needs no
 * audio-side range copy; the settings ride the id. The two companions
 * that DO exist live in AudioTrackOps: afterSplit (the new right piece
 * inherits the settings row) and exportStartMs (moved audio's export
 * truth, read from the timeline clip's CURRENT timelineRange.start).
 * An audio timeline clip WITHOUT an AudioClip row plays at unity
 * (0 dB, no fades) — the absent-row default; its sample rate comes
 * from the source at the codec layer.
 *
 * AUD-02 — THE ORIGINAL-AUDIO LINK STATE. Every video source's
 * ORIGINAL audio has an explicit link state, recorded PER SOURCE
 * (keyed by the video clip's sourceId) and persisted in project.json's
 * "audio" section. The explicit record is the point: without it, a
 * REMOVED source would fall back to the absent-record default
 * (ATTACHED) the next time the project opens — the removed audio would
 * come back ("eski proje açıldığında kaldırılmış sesin geri gelmesini
 * önleyen açık durum kaydı"). The four states:
 *
 *   ATTACHED       the original audio plays (the default for a source
 *                  with no record — a fresh import)
 *   DETACHED       the original audio is sidelined but RESTORABLE
 *                  (detach is reversible: restore returns ATTACHED)
 *   REMOVED        explicit removal. THE INVARIANT: no transition maps
 *                  REMOVED back to ATTACHED, and the record persists
 *                  through save/load verbatim — the removed audio
 *                  never resurrects on project reopen. The exits are
 *                  reLink (bind an edited recording) or undo
 *                  (whole-record replacement outside this machine).
 *   LINKED_EDITED  an edited audio recording plays instead, bound by
 *                  editedAudioId ("mevcut düzenlenmiş ses kaydı varsa
 *                  ona bağlan" — the caller resolves WHICH recording
 *                  exists; the model binds it).
 *
 * TRANSITIONS (AudioTrackOps, pure): detach sidelines (ATTACHED or
 * LINKED_EDITED -> DETACHED, idempotent elsewhere, a REMOVED record
 * untouched — nothing left to sideline); restore brings the original
 * back (DETACHED or LINKED_EDITED -> ATTACHED, idempotent on ATTACHED,
 * REFUSED on REMOVED — the invariant); remove is total and idempotent
 * (any state -> REMOVED); reLink binds an edited recording (any state
 * -> LINKED_EDITED, a blank target refused).
 *
 * CANONICAL FORM: AudioProject.links holds ONLY non-ATTACHED records —
 * ATTACHED is the absent-record default, so an explicit ATTACHED record
 * and a missing record are the same thing. The project-level ops and
 * AudioStore's parse both keep the map canonical (restore removes the
 * entry; the store renders only non-ATTACHED records and normalizes
 * explicit-ATTACHED records to absent on load).
 */

/**
 * The link state of a source's ORIGINAL audio (AUD-02). Explicit and
 * persisted per sourceId: without the record, a REMOVED source would
 * fall back to ATTACHED on reopen and the removed audio would come
 * back — the record is what prevents that.
 */
enum class AudioLinkState {
    /** The original audio plays — the default for a source with no record. */
    ATTACHED,
    /** The original audio is sidelined but restorable (detach is reversible). */
    DETACHED,
    /**
     * Explicit removal. THE INVARIANT: no transition maps REMOVED back
     * to ATTACHED, and the record persists through save/load verbatim —
     * the removed audio never resurrects on project reopen. The exits
     * are reLink (bind an edited recording) or undo (whole-record
     * replacement outside the transition machine).
     */
    REMOVED,
    /**
     * An edited audio recording plays instead, bound by editedAudioId
     * ("mevcut düzenlenmiş ses kaydı varsa ona bağlan" — the caller
     * resolves which recording exists; the model binds it).
     */
    LINKED_EDITED,
}

/**
 * One source's original-audio link record (AUD-02), keyed by the video
 * clip's sourceId in [AudioProject.links]. Defaults to ATTACHED — an
 * absent record and an explicit ATTACHED record are equivalent (the
 * canonical-form rule in the file KDoc: the map and the persisted file
 * hold only non-ATTACHED records).
 */
data class AudioLinkRecord(
    val state: AudioLinkState = AudioLinkState.ATTACHED,
    /**
     * The edited audio recording this source is bound to. Set ONLY in
     * LINKED_EDITED (the init invariant below). Opaque id — the future
     * edited-audio-recording store owns what it points at; this model
     * only binds it.
     */
    val editedAudioId: String? = null,
) {
    init {
        require(state == AudioLinkState.LINKED_EDITED || editedAudioId == null) {
            "editedAudioId is only valid in LINKED_EDITED (state=$state)"
        }
        require(state != AudioLinkState.LINKED_EDITED || !editedAudioId.isNullOrBlank()) {
            "LINKED_EDITED requires an editedAudioId"
        }
    }
}

/**
 * The audio-specific settings of ONE audio clip (AUD-03) — the
 * per-clip state the timeline core does not model. Placement lives in
 * the timeline (see the file KDoc, PLACEMENT OWNERSHIP BOUNDARY): this
 * row joins by id and rides every timeline edit untouched.
 */
data class AudioClip(
    /**
     * == the audio TimelineClip's id (the join key into the timeline
     * core; the video clip's linkedAudioId points at the same id).
     */
    val id: String,
    /** The MediaSource the audio came from (the timeline clip's sourceId). */
    val sourceId: String,
    /**
     * The SOURCE sample rate in Hz, observed at import (AUD-06):
     * REQUIRED data — 44100, 48000, or whatever the source really is.
     * Deriving time from sample counts REQUIRES this number (see
     * AudioTrackOps.samplesToMs); assuming a fixed 48 kHz is the bug
     * this field exists to prevent. The codec layer re-probes the
     * source at export; this is the persisted observation and the
     * model's time-contract anchor.
     */
    val sampleRateHz: Int,
    /** Gain in dBFS. Default 0 = unity; positive boosts, negative attenuates. */
    val gainDb: Float = 0f,
    /**
     * Fade-in duration in ms. 0 = no fade. Stored INTENT: a fade longer
     * than the clip's current (possibly trimmed) duration clamps at
     * render time — the codec mission's rule; trim never rewrites it.
     */
    val fadeInMs: Int = 0,
    /** Fade-out duration in ms. 0 = no fade. Same stored-intent rule as [fadeInMs]. */
    val fadeOutMs: Int = 0,
    /**
     * Stereo pan, -1 (full left) .. 0 (center) .. +1 (full right) —
     * AUD-07 "stereo pan" (şartname §6 Ses). Rendered by the
     * constant-power law in AudioPlan.panGains (TIMELINE_AUDIO_PLAN
     * §3.2). Default 0 = center, persisted OMIT-WHEN-DEFAULT so files
     * written before the field stay byte-identical and old rows load as
     * center (the plan's §6.3 item 4 migration, landed 2026-11-23).
     */
    val pan: Float = 0f,
    /**
     * Clip mute (AUD-07 "track mute" at clip granularity — the timeline
     * core's track rows carry no audio state). A muted clip contributes
     * silence in preview AND export (the mix reads ClipAudioState.of).
     * Default false, omit-when-default like [pan].
     */
    val muted: Boolean = false,
) {
    init {
        require(pan in -1.0f..1.0f) { "pan $pan outside -1..1" }
        require(sampleRateHz > 0) {
            "sampleRateHz must be > 0 (AUD-06: the rate is required data, never assumed)"
        }
        require(fadeInMs >= 0) { "fadeInMs must be >= 0" }
        require(fadeOutMs >= 0) { "fadeOutMs must be >= 0" }
    }
}

/**
 * The audio half of a project: per-source link records (AUD-02) and
 * per-clip settings rows (AUD-03). Immutable — every edit returns a
 * copy (AudioTrackOps); AudioStore persists it into project.json's
 * "audio" section, preserving the links' encounter order.
 */
data class AudioProject(
    /**
     * Original-audio link state per sourceId, in canonical form: only
     * non-ATTACHED records (ATTACHED is the absent default). A
     * LinkedHashMap keeps the encounter order stable for rendering.
     */
    val links: Map<String, AudioLinkRecord> = emptyMap(),
    /** Audio settings rows; an absent row = unity gain, no fades (the default). */
    val clips: List<AudioClip> = emptyList(),
    /**
     * Master gain in dBFS (AUD-07 "master dBFS"), applied AFTER the clip
     * sum and BEFORE saturation (TIMELINE_AUDIO_PLAN §3.3). 0 = unity;
     * persisted omit-when-default (a pre-field file loads as unity).
     */
    val masterGainDb: Float = 0f,
    /**
     * Master mute: the whole mix renders silence (AUD-07). "Dışa aktarmada
     * mute ve track seçimi proje snapshot'ından alınır" — the flag lives in
     * the project file, so an export reads the same value the preview did.
     */
    val masterMuted: Boolean = false,
) {
    /** The source's link record, or the ATTACHED default when absent. */
    fun link(sourceId: String): AudioLinkRecord =
        links[sourceId] ?: AudioLinkRecord()

    /** Settings row by clip id — the join key into the timeline core. */
    fun clip(id: String): AudioClip? = clips.firstOrNull { it.id == id }
}
