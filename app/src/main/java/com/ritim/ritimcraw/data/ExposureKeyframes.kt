package com.ritim.ritimcraw.data

import kotlin.math.max
import kotlin.math.min

/**
 * Manual exposure keyframes + the EV composition rule (şartname AE-01:
 * "Tek klip ve timeline'da manuel exposure keyframe. Zamanı taşınabilir,
 * değer düzenlenebilir, instant veya ayarlanabilir yumuşak geçiş"; AE-09:
 * "Analiz ve grade bağımsız ... öneri totalEV = baseEV + autoEV +
 * keyframeEV"; AE-05's "maksimum EV" bound). Landed 2026-11-23 as a PURE
 * model + JSON codec; nothing renders it yet — the develop wiring (the
 * per-frame EV handed to the grade parcel) and the keyframe lane UI are
 * the grade/editor owners' (GradeStore, GradeParcel and the editor are
 * single-writer files).
 *
 * TIME AXIS: keyframes live on the clip's SOURCE time in microseconds (the
 * source PTS domain, şartname zaman modeli: "Video source PTS, timeline
 * zamanı ve audio sample konumu üç ayrı domain"). Source time, not a frame
 * index, so a keyframe stays on the same moment of the shot when the
 * project fps changes or the clip is trimmed (the TL-13 "süre saniye
 * cinsinden korunur" stance); the timeline maps its clip range onto it.
 *
 * TRANSITIONS: a keyframe says how its value is REACHED from the previous
 * one —
 *  - INSTANT: the value steps at the keyframe's time (a cut in exposure);
 *  - SMOOTH(rampUs): the value eases from the previous keyframe's value,
 *    starting rampUs BEFORE the keyframe and arriving exactly at it
 *    (smoothstep easing — zero slope at both ends, no visible kink). A ramp
 *    longer than the gap to the previous keyframe is clamped to that gap
 *    (stored intent, clamped at evaluation — the AudioClip fade rule).
 * Before the first keyframe the first value holds; after the last, the
 * last holds. No keyframes = 0 EV (the neutral, byte-identical default).
 */
data class ExposureKeyframe(
    /** Stable id: survives moves and value edits (the timeline-clip id rule). */
    val id: String,
    /** Position on the clip's source time, microseconds. */
    val timeUs: Long,
    /** Keyframe exposure offset in EV stops (added to the grade's base EV). */
    val ev: Float,
    val transition: Transition = Transition.Instant,
) {
    sealed class Transition {
        object Instant : Transition() {
            override fun toString() = "Instant"
        }

        data class Smooth(val rampUs: Long) : Transition() {
            init {
                require(rampUs >= 0) { "rampUs must be >= 0" }
            }
        }
    }

    init {
        require(ev.isFinite()) { "ev must be finite" }
    }
}

/** An immutable, always time-sorted keyframe lane for one clip. */
data class ExposureKeyframes(val keys: List<ExposureKeyframe> = emptyList()) {

    init {
        require(keys.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) { "keys must be time-sorted" }
        require(keys.map { it.id }.toSet().size == keys.size) { "duplicate keyframe id" }
    }

    val isEmpty: Boolean get() = keys.isEmpty()

    /** The keyframe EV at source time [tUs] (see the class KDoc for the rules). */
    fun evAt(tUs: Long): Float {
        if (keys.isEmpty()) return 0f
        if (tUs <= keys.first().timeUs) {
            // Before (or at) the first key: its value holds.
            return keys.first().ev
        }
        // The last key at or before t, and the next key after t.
        val i = keys.indexOfLast { it.timeUs <= tUs }
        val prev = keys[i]
        if (i == keys.lastIndex) return prev.ev
        val next = keys[i + 1]
        return when (val tr = next.transition) {
            ExposureKeyframe.Transition.Instant -> prev.ev
            is ExposureKeyframe.Transition.Smooth -> {
                val gap = next.timeUs - prev.timeUs
                val ramp = min(tr.rampUs, gap)
                val rampStart = next.timeUs - ramp
                if (ramp <= 0L || tUs < rampStart) {
                    prev.ev
                } else {
                    val x = ((tUs - rampStart).toDouble() / ramp).coerceIn(0.0, 1.0)
                    val s = x * x * (3.0 - 2.0 * x) // smoothstep
                    (prev.ev + (next.ev - prev.ev) * s).toFloat()
                }
            }
        }
    }

    /** Adds [key] (its id must be new); keeps time order (stable for equal times). */
    fun add(key: ExposureKeyframe): ExposureKeyframes {
        require(keys.none { it.id == key.id }) { "keyframe id ${key.id} exists" }
        return ExposureKeyframes(sorted(keys + key))
    }

    /** Moves keyframe [id] to [timeUs] ("zamanı taşınabilir"); unknown id = unchanged. */
    fun move(id: String, timeUs: Long): ExposureKeyframes =
        if (keys.none { it.id == id }) this
        else ExposureKeyframes(sorted(keys.map { if (it.id == id) it.copy(timeUs = timeUs) else it }))

    /** Edits keyframe [id]'s value ("değer düzenlenebilir"); unknown id = unchanged. */
    fun setEv(id: String, ev: Float): ExposureKeyframes =
        ExposureKeyframes(keys.map { if (it.id == id) it.copy(ev = ev) else it })

    /** Sets how keyframe [id] is reached (instant / smooth ramp). */
    fun setTransition(id: String, transition: ExposureKeyframe.Transition): ExposureKeyframes =
        ExposureKeyframes(keys.map { if (it.id == id) it.copy(transition = transition) else it })

    fun remove(id: String): ExposureKeyframes = ExposureKeyframes(keys.filterNot { it.id == id })

    companion object {
        private fun sorted(list: List<ExposureKeyframe>) = list.sortedBy { it.timeUs }

        /**
         * Renders the lane as a JSON array (the shape a future GradeK
         * "exposureKeys" field would carry, omit-when-empty):
         * [{"id":..,"t":us,"ev":..} | +"smoothUs":us].
         */
        fun render(k: ExposureKeyframes): String = buildString {
            append('[')
            k.keys.forEachIndexed { i, key ->
                if (i > 0) append(',')
                append("{\"id\":").append(ProjectJson.quote(key.id))
                append(",\"t\":").append(key.timeUs)
                append(",\"ev\":").append(GradeStore.trimFloat(key.ev))
                val tr = key.transition
                if (tr is ExposureKeyframe.Transition.Smooth) append(",\"smoothUs\":").append(tr.rampUs)
                append('}')
            }
            append(']')
        }

        /**
         * Parses [render]'s array, ROW-TOLERANT: a row missing id/t/ev, a
         * non-finite ev, a negative ramp or a repeated id is skipped (one
         * bad row never empties the lane). Malformed JSON throws
         * IllegalArgumentException (the store-level caller decides).
         */
        fun parse(json: String): ExposureKeyframes {
            val p = JsonParser(json)
            val v = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            val arr = v as? List<*> ?: throw IllegalArgumentException("not an array")
            val out = ArrayList<ExposureKeyframe>()
            val seen = HashSet<String>()
            for (row in arr) {
                val m = row as? Map<*, *> ?: continue
                val id = m["id"] as? String ?: continue
                val t = (m["t"] as? Double)?.toLong() ?: continue
                val ev = (m["ev"] as? Double)?.toFloat()?.takeIf { it.isFinite() } ?: continue
                val smooth = (m["smoothUs"] as? Double)?.toLong()
                if (smooth != null && smooth < 0) continue
                if (!seen.add(id)) continue
                val tr = if (smooth == null) ExposureKeyframe.Transition.Instant
                else ExposureKeyframe.Transition.Smooth(smooth)
                out.add(ExposureKeyframe(id, t, ev, tr))
            }
            return ExposureKeyframes(sorted(out))
        }
    }
}

/**
 * AE-09's composition: totalEV = baseEV + autoEV + keyframeEV, where
 * baseEV is the grade's exposure slider, autoEV the (future) analysis
 * curve and keyframeEV the manual lane — kept as three INDEPENDENT terms
 * so reset/preset transfer can keep or drop each one ("Analiz ve grade
 * bağımsız"). [maxAbsEv] is AE-05's maximum-EV bound applied to the
 * CORRECTION terms (auto + keyframe), never to the user's own base slider:
 * the bound limits what automation and keys may add, not what the user
 * dialed in. null = unbounded.
 */
object ExposureComposition {
    fun totalEv(baseEv: Float, autoEv: Float = 0f, keyframeEv: Float = 0f, maxAbsEv: Float? = null): Float {
        var correction = autoEv + keyframeEv
        if (maxAbsEv != null) {
            require(maxAbsEv >= 0f) { "maxAbsEv must be >= 0" }
            correction = max(-maxAbsEv, min(maxAbsEv, correction))
        }
        return baseEv + correction
    }
}
