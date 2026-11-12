package com.ritim.ritimcraw.data.audio

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure audio rules (AUD-02/AUD-03/AUD-06) — the functional core the
 * future audio UI calls and AudioStore persists. All functions are
 * pure: they take an AudioProject (or plain values) and return the
 * edited copy; nothing mutates in place. No Android imports — the
 * whole object is JVM-testable headless (the TimelineOps precedent).
 *
 * Sections: the AUD-02 link-state transition machine, the AUD-03 clip
 * settings rules (trim preservation, moved-audio export truth, split
 * inheritance), and the AUD-06 sample-accurate time contract.
 */
object AudioTrackOps {

    // ---------------------------------------------------------------
    // AUD-02: the original-audio link-state machine
    // ---------------------------------------------------------------

    /**
     * DETACH sidelines the original audio of [sourceId] (AUD-02):
     * ATTACHED or LINKED_EDITED -> DETACHED, restorable by [restore].
     * Idempotent on DETACHED; a REMOVED record stays REMOVED (nothing
     * left to sideline — the no-resurrection invariant). A missing
     * record is created as DETACHED (detach is what first moves a
     * source away from the absent-record ATTACHED default).
     */
    fun detach(project: AudioProject, sourceId: String): AudioProject {
        val current = project.link(sourceId)
        if (current.state == AudioLinkState.DETACHED ||
            current.state == AudioLinkState.REMOVED
        ) {
            return project
        }
        return project.copy(
            links = project.links + (sourceId to AudioLinkRecord(state = AudioLinkState.DETACHED)),
        )
    }

    /**
     * RESTORE brings the original audio back (AUD-02): DETACHED or
     * LINKED_EDITED -> ATTACHED, idempotent on ATTACHED. REFUSED on
     * REMOVED — returns the project unchanged: restore is the
     * detach-inverse, NOT a removal-inverse; removal is explicit and
     * its record must never silently fall back to ATTACHED (the
     * no-resurrection invariant). The only exits from REMOVED are
     * [reLink] (bind an edited recording) or an undo that replaces
     * the whole record.
     */
    fun restore(project: AudioProject, sourceId: String): AudioProject {
        val current = project.link(sourceId)
        if (current.state == AudioLinkState.REMOVED ||
            current.state == AudioLinkState.ATTACHED
        ) {
            return project
        }
        // Canonical form: ATTACHED is the absent-record default, so
        // restore REMOVES the record rather than storing ATTACHED.
        return project.copy(links = project.links - sourceId)
    }

    /**
     * REMOVE is total and explicit (AUD-02): any state -> REMOVED,
     * idempotent. The record persists through save/load verbatim and
     * no transition maps it back to ATTACHED — the removed audio never
     * comes back when an old project reopens ("kaldırılmış sesin
     * geri gelmesini önleyen açık durum kaydı").
     */
    fun remove(project: AudioProject, sourceId: String): AudioProject =
        project.copy(
            links = project.links + (sourceId to AudioLinkRecord(state = AudioLinkState.REMOVED)),
        )

    /**
     * RE-LINK binds an EDITED audio recording to the source (AUD-02,
     * "yeniden bağla" / "mevcut düzenlenmiş ses kaydı varsa ona
     * bağlan"): any state -> LINKED_EDITED, carrying editedAudioId.
     * The caller resolves WHICH recording exists (the edited-audio
     * store is a later mission); the model only binds the id. A blank
     * [editedAudioId] is refused (returns the project unchanged) —
     * LINKED_EDITED without a target would violate the record
     * invariant. reLink is the one sanctioned exit from REMOVED:
     * binding an edited recording is a NEW explicit choice, not a
     * resurrection of the removed original.
     */
    fun reLink(project: AudioProject, sourceId: String, editedAudioId: String): AudioProject {
        if (editedAudioId.isBlank()) return project
        return project.copy(
            links = project.links + (
                sourceId to AudioLinkRecord(
                    state = AudioLinkState.LINKED_EDITED,
                    editedAudioId = editedAudioId,
                )
                ),
        )
    }

    // ---------------------------------------------------------------
    // AUD-03: clip settings — trim preservation, split, export truth
    // ---------------------------------------------------------------

    /**
     * Sets gain/fades on one clip's settings row (AUD-03). Creates the
     * row when absent (a clip the user has never touched plays at
     * unity — the absent-row default). The row's id and sourceId are
     * identity: they never change here.
     *
     * [sampleRateHz] is REQUIRED (AUD-06): the caller supplies the
     * source's real rate, observed at import — the model never assumes
     * 48 kHz and never guesses. When the row already exists, its
     * persisted rate is kept (the observations agree; the stored one
     * is the anchor) and 0 may be passed as "keep the existing rate".
     * Creating a NEW row with a non-positive rate throws
     * IllegalArgumentException from AudioClip's invariant — an
     * unknown rate must fail loud, not silently assume 48 kHz.
     */
    fun setSettings(
        project: AudioProject,
        clipId: String,
        sourceId: String,
        sampleRateHz: Int,
        gainDb: Float,
        fadeInMs: Int,
        fadeOutMs: Int,
    ): AudioProject {
        val existing = project.clip(clipId)
        val row = AudioClip(
            id = clipId,
            sourceId = sourceId,
            sampleRateHz = if (sampleRateHz > 0) sampleRateHz else existing?.sampleRateHz ?: 0,
            gainDb = gainDb,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
        )
        val clips = if (existing == null) {
            project.clips + row
        } else {
            project.clips.map { if (it.id == clipId) row else it }
        }
        return project.copy(clips = clips)
    }

    /**
     * TRIM PRESERVES SETTINGS (AUD-03, "split ve trim'le aralık, gain
     * ve fade gibi mevcut ayarlar korunur"). This is the pure rule the
     * timeline's trim path satisfies BY CONSTRUCTION: AudioClip carries
     * no range, so a timeline trim (which edits the timeline clip's
     * ranges) cannot touch the settings row — gain and fades survive
     * every trim and split untouched because they ride the id, not the
     * range. This function is the rule's proof surface: it asserts the
     * settings row is UNCHANGED after any timeline edit and returns
     * the (possibly absent) row for the caller to check.
     */
    fun settingsAfterEdit(project: AudioProject, clipId: String): AudioClip? =
        project.clip(clipId)

    /**
     * SPLIT SETTINGS INHERITANCE (AUD-03): the timeline's split turns
     * one audio clip into two; the NEW right piece inherits the left
     * piece's settings row (gain and fades carry over — the same
     * "mevcut ayarlar korunur" rule at split time). The left piece
     * KEEPS its row (id preserved — the timeline's convention that the
     * left piece keeps the clip id); the right piece gets a NEW row
     * with the right piece's id and the SAME gain/fades/sample rate.
     *
     * Returns the project with the right row appended; a missing left
     * row means the left piece was at the unity default — nothing to
     * inherit, the project returns unchanged (the right piece plays at
     * unity too, the absent-row default).
     */
    fun afterSplit(
        project: AudioProject,
        leftClipId: String,
        rightClipId: String,
    ): AudioProject {
        val left = project.clip(leftClipId) ?: return project
        if (project.clip(rightClipId) != null) return project
        val right = left.copy(id = rightClipId)
        return project.copy(clips = project.clips + right)
    }

    /**
     * MOVED AUDIO'S EXPORT TRUTH (AUD-03, "ses sağa taşındığında
     * export, sesin yeni timeline başlangıcını kullanır"). When audio
     * is moved on the timeline, export must start it at its NEW
     * timeline position. PLACEMENT IS THE TIMELINE'S — this model
     * stores none — so the rule reads the timeline clip's CURRENT
     * timelineStart (project frame grid) and converts it to
     * milliseconds on the SAMPLE-ACCURATE contract below (the frame
     * grid's frame duration, expressed through the project fps).
     *
     * @param timelineStartFrames the audio timeline clip's CURRENT
     *   timelineRange.start, in project frames (the timeline owns it).
     * @param projectFps the PROJECT frame grid's fps (the timeline's
     *   timebase — TimelineOutput.fps).
     * @return the export start in ms, or null when projectFps is not
     *   positive (the caller must not guess a grid).
     */
    fun exportStartMs(timelineStartFrames: Double, projectFps: Int): Double? {
        if (projectFps <= 0) return null
        return timelineStartFrames * 1000.0 / projectFps
    }

    // ---------------------------------------------------------------
    // AUD-06: the sample-accurate time contract
    // ---------------------------------------------------------------

    /**
     * THE TIME CONTRACT (AUD-06): deriving time from a sample count
     * REQUIRES the source's real sample rate — NEVER a fixed 48 kHz
     * assumption. samplesToMs(44100, 44100) is 1000 ms, not the
     * ~918.75 ms a 48k assumption would produce; a 44.1 kHz source
     * misread at 48 kHz drifts ~10% and desyncs A/V within seconds.
     * The rule: the sample rate travels WITH the sample count (the
     * [AudioClip.sampleRateHz] field is the persisted anchor), and
     * every conversion goes through this pair.
     *
     * RESAMPLING (the future mission) slots in HERE: sources at
     * 44.1 kHz, 48 kHz and other tested rates will be resampled to a
     * common mix rate by timestamp. That mission converts on this
     * same contract — samples * 1000.0 / sampleRate — with the
     * SOURCE rate for source-domain samples and the MIX rate for
     * mix-domain ones; this function stays the single conversion
     * point, and no caller may substitute a hardcoded rate for the
     * parameter.
     *
     * @throws IllegalArgumentException when sampleRateHz is not positive
     *   (an unknown rate must FAIL LOUD, not silently assume 48 kHz).
     */
    fun samplesToMs(samples: Long, sampleRateHz: Int): Double {
        require(sampleRateHz > 0) {
            "sampleRateHz must be > 0 (AUD-06: never assume 48 kHz — the rate is required data)"
        }
        return samples * 1000.0 / sampleRateHz
    }

    /**
     * The inverse conversion (AUD-06): ms -> sample count at the given
     * rate, ROUNDED to the nearest sample (sample-accurate placement
     * for the future resample/mix mission). Same contract as
     * [samplesToMs]: the rate is a required parameter, never assumed.
     *
     * @throws IllegalArgumentException when sampleRateHz is not positive.
     */
    fun msToSamples(ms: Double, sampleRateHz: Int): Long {
        require(sampleRateHz > 0) {
            "sampleRateHz must be > 0 (AUD-06: never assume 48 kHz — the rate is required data)"
        }
        return kotlin.math.round(ms * sampleRateHz / 1000.0).toLong()
    }

    /**
     * The AUD-06 guard as a query: true when [sampleRateHz] is one of
     * the rates the codec mission has TESTED (44.1 kHz and 48 kHz are
     * the two required ones). Untested exotic rates are the resample
     * mission's business; this lets the UI flag them honestly.
     */
    fun isTestedRate(sampleRateHz: Int): Boolean =
        sampleRateHz == 44100 || sampleRateHz == 48000
}
