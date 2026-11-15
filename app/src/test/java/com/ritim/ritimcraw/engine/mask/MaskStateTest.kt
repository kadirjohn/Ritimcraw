package com.ritim.ritimcraw.engine.mask

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regional-grade mask model's acceptance tests (night research
 * 2026-11-16): the neutral identity, the 0/1/invert/feather edges, the
 * overlap ordering, the NaN/size/count rejections, the serialization
 * round-trip, the cache-key stability, and the source <-> display
 * coordinate contracts. Pure JVM — no Android, no native lib.
 */
class MaskStateTest {

    // ---- fixtures ----

    private fun radial(
        id: String = "m",
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        rx: Float = 0.25f,
        ry: Float = 0.25f,
        angleDeg: Float = 0f,
        feather: Float = 0f,
        invert: Boolean = false,
        enabled: Boolean = true,
        delta: MaskGradeDelta = MaskGradeDelta(),
    ) = MaskK(
        id = id,
        shape = MaskShape.Radial(cx, cy, rx, ry, angleDeg),
        feather = feather,
        invert = invert,
        enabled = enabled,
        delta = delta,
    )

    private fun linear(
        id: String = "m",
        x0: Float = 0.5f,
        y0: Float = 0f,
        x1: Float = 0.5f,
        y1: Float = 1f,
        feather: Float = 0f,
        invert: Boolean = false,
        enabled: Boolean = true,
        delta: MaskGradeDelta = MaskGradeDelta(),
    ) = MaskK(
        id = id,
        shape = MaskShape.Linear(x0, y0, x1, y1),
        feather = feather,
        invert = invert,
        enabled = enabled,
        delta = delta,
    )

    private val ev = MaskGradeDelta(exposureEv = 1f)

    // ---- THE NEUTRAL IDENTITY (the acceptance's "empty/off = NEUTRAL") ----

    @Test
    fun `empty list is weight-0 coverage and neutral delta everywhere`() {
        val s = MaskState()
        assertTrue(s.isNeutralForRender)
        // Coverage of NOTHING is 0 (no region is masked)...
        assertEquals(0f, s.coverageAt(0.5f, 0.5f), 0f)
        assertEquals(0f, s.coverageAt(0f, 0f), 0f)
        assertEquals(0f, s.coverageAt(1f, 1f), 0f)
        // ...and the delta is the neutral delta at every point, so
        // applyTo passes the grade through with factor 1 everywhere.
        for (p in listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f, 0.25f to 0.75f)) {
            assertEquals(MaskGradeDelta(), s.deltaAt(p.first, p.second))
        }
    }

    @Test
    fun `empty list leaves the grade unchanged`() {
        val g = GradeK(exposureEv = 0.7f, tempK = 6100f, saturation = -20f)
        assertEquals(g, MaskState().deltaAt(0.3f, 0.4f).applyTo(g, weight = 1f))
    }

    @Test
    fun `all-disabled list is the neutral for render`() {
        val s = MaskState(
            listOf(
                radial(id = "r", enabled = false, delta = ev),
                linear(id = "l", enabled = false, delta = ev),
            )
        )
        assertTrue(s.isNeutralForRender)
        assertEquals(0f, s.coverageAt(0.5f, 0.5f), 0f)
        assertEquals(MaskGradeDelta(), s.deltaAt(0.5f, 0.5f))
    }

    @Test
    fun `neutral-delta masks draw coverage but change no grade`() {
        // The user's shape-in-progress: a mask with no knobs yet. It
        // still draws coverage (the UI's shape preview) but IS
        // render-neutral — nothing reaches the develop chain.
        val s = MaskState(listOf(radial(delta = MaskGradeDelta())))
        assertTrue(s.isNeutralForRender)
        assertEquals("", s.signature())
        assertEquals(1f, s.coverageAt(0.5f, 0.5f), 0f)
        // The delta is neutral everywhere and the grade is untouched.
        val g = GradeK(exposureEv = -1.5f)
        assertEquals(MaskGradeDelta(), s.deltaAt(0.5f, 0.5f))
        assertEquals(g, s.deltaAt(0.5f, 0.5f).applyTo(g))
    }

    @Test
    fun `neutral delta applyTo is the exact identity even for out-of-range grades`() {
        // applyTo's exact-identity early return: a neutral delta never
        // clamps a grade whose own values sit outside the ranges.
        val odd = GradeK(exposureEv = 99f, highlights = 250f, tempK = 90000f)
        assertEquals(odd, MaskGradeDelta().applyTo(odd))
    }

    // ---- the 0/1 edges ----

    @Test
    fun `radial mask is 1 at the center and 0 outside the boundary`() {
        val m = radial(delta = ev)
        assertEquals(1f, m.weightAt(0.5f, 0.5f), 0f)
        // Exactly ON the boundary (q = 1): the inside owns the edge.
        assertEquals(1f, m.weightAt(0.75f, 0.5f), 1e-6f)
        assertEquals(1f, m.weightAt(0.5f, 0.75f), 1e-6f)
        // Just outside: 0.
        assertEquals(0f, m.weightAt(0.76f, 0.5f), 0f)
        assertEquals(0f, m.weightAt(0.5f, 0.76f), 0f)
        assertEquals(0f, m.weightAt(0f, 0f), 0f)
    }

    @Test
    fun `linear mask is 1 at the start line and 0 at the end line`() {
        // Top-to-bottom gradient: 1 at y=0, 0 at y=1.
        val m = linear(delta = ev)
        assertEquals(1f, m.weightAt(0.5f, 0f), 0f)
        assertEquals(0f, m.weightAt(0.5f, 1f), 0f)
        // The ramp is a perpendicular projection: constant along the
        // start line.
        assertEquals(1f, m.weightAt(0.1f, 0f), 1e-6f)
        // Feather 0 is the hard cut at the AXIS MIDPOINT, and the cut
        // belongs to the inside: the whole first half of the axis (t
        // in [0, 0.5]) carries weight 1.
        assertEquals(1f, m.weightAt(0.8f, 0.5f), 1e-6f)
        assertEquals(1f, m.weightAt(0.5f, 1e-7f), 1e-3f)
        assertEquals(0f, m.weightAt(0.5f, 0.500001f), 1e-3f)
    }

    // ---- the invert edge ----

    @Test
    fun `invert complements the weight`() {
        val plain = radial(delta = ev)
        val flipped = radial(delta = ev, invert = true)
        for (p in listOf(0.5f to 0.5f, 0.75f to 0.5f, 0.9f to 0.5f, 0f to 0f)) {
            assertEquals(
                1f - plain.weightAt(p.first, p.second),
                flipped.weightAt(p.first, p.second),
                1e-6f,
            )
        }
        // The inverted full-frame mask still reaches 1 somewhere (the
        // complement of an inside disk is the frame's outer region).
        assertEquals(1f, flipped.weightAt(0f, 0f), 0f)
    }

    @Test
    fun `invert flips which region carries the delta`() {
        val s = MaskState(listOf(radial(delta = ev, invert = true)))
        // Outside the disk: full delta. Inside: none.
        assertEquals(0f, s.deltaAt(0.5f, 0.5f).exposureEv, 0f)
        assertEquals(1f, s.deltaAt(0f, 0f).exposureEv, 0f)
    }

    // ---- the feather extremes ----

    @Test
    fun `feather 0 is the hard cut and feather 1 is the maximally soft ramp`() {
        // Radial, feather 0: hard.
        val hard = radial(feather = 0f, delta = ev)
        assertEquals(1f, hard.weightAt(0.749f, 0.5f), 1e-3f)
        assertEquals(0f, hard.weightAt(0.751f, 0.5f), 1e-3f)
        // Radial, feather 1: the band covers the whole disk from the
        // center (q in [0,1]); the center itself is exactly 1, the
        // boundary exactly 0, the midpoint q=0.5 is the smoothstep
        // midpoint 0.5.
        val soft = radial(feather = 1f, delta = ev)
        assertEquals(1f, soft.weightAt(0.5f, 0.5f), 1e-6f)
        assertEquals(0.5f, soft.weightAt(0.625f, 0.5f), 1e-3f)
        assertEquals(0f, soft.weightAt(0.75f, 0.5f), 1e-6f)
        // Linear, feather 1: the ramp spans the whole axis (t in
        // [0,1]); the start is 1, the midpoint 0.5, the end 0.
        val softL = linear(feather = 1f, delta = ev)
        assertEquals(1f, softL.weightAt(0.5f, 0f), 0f)
        assertEquals(0.5f, softL.weightAt(0.5f, 0.5f), 1e-6f)
        assertEquals(0f, softL.weightAt(0.5f, 1f), 0f)
    }

    @Test
    fun `feather softens monotonically between the edges`() {
        val m = radial(feather = 0.5f, delta = ev)
        // The band is q in [0.5, 1] -> x in [0.625, 0.75].
        var last = 1f
        var x = 0.62f
        while (x <= 0.76f) {
            val w = m.weightAt(x, 0.5f)
            assertTrue("weight must be finite", w.isFinite())
            assertTrue("weight must fall monotonically", w <= last + 1e-6f)
            last = w
            x += 0.01f
        }
        assertEquals(0f, last, 1e-3f)
    }

    // ---- the overlap ordering (the blend rule) ----

    @Test
    fun `overlapping masks stack additively per knob`() {
        // Two half-overlapping disks each carrying +1 EV: at a point
        // where both weights are 1, the delta is +2 EV.
        val a = radial(id = "a", cx = 0.4f, rx = 0.3f, ry = 0.3f, delta = ev)
        val b = radial(id = "b", cx = 0.6f, rx = 0.3f, ry = 0.3f, delta = ev)
        val s = MaskState(listOf(a, b))
        assertEquals(1f, a.weightAt(0.4f, 0.5f), 0f)
        assertEquals(1f, b.weightAt(0.6f, 0.5f), 0f)
        // The overlap point sits inside both disks.
        assertEquals(2f, s.deltaAt(0.5f, 0.5f).exposureEv, 1e-3f)
    }

    @Test
    fun `the blend is order-independent (reordering never changes the render)`() {
        val a = radial(id = "a", cx = 0.4f, rx = 0.3f, ry = 0.3f, delta = ev)
        val b = MaskK(
            id = "b",
            shape = MaskShape.Linear(0f, 1f, 1f, 0f),
            feather = 0.3f,
            invert = true,
            delta = MaskGradeDelta(shadows = 40f, tint = -15f),
        )
        val ab = MaskState(listOf(a, b))
        val ba = MaskState(listOf(b, a))
        for (p in listOf(0.5f to 0.5f, 0.2f to 0.3f, 0.9f to 0.8f, 0.35f to 0.65f)) {
            assertEquals(ab.deltaAt(p.first, p.second), ba.deltaAt(p.first, p.second))
            assertEquals(
                ab.coverageAt(p.first, p.second),
                ba.coverageAt(p.first, p.second),
                0f,
            )
        }
        // The cache signature is order-independent too.
        assertEquals(ab.signature(), ba.signature())
    }

    @Test
    fun `coverage is the union blend`() {
        // Two disjoint disks: between them the union is 1 at both
        // centers; at a far point 0. Overlapping half-weights: the
        // 1-(1-w0)(1-w1) union, NOT the sum (which would exceed 1).
        val a = radial(id = "a", cx = 0.3f, rx = 0.2f, ry = 0.2f, feather = 1f)
        val b = radial(id = "b", cx = 0.7f, rx = 0.2f, ry = 0.2f, feather = 1f)
        val s = MaskState(listOf(a, b))
        assertEquals(1f, s.coverageAt(0.3f, 0.5f), 1e-6f)
        assertEquals(0f, s.coverageAt(0.5f, 0.5f), 1e-3f)
        // Half + half at one point through overlapping soft edges.
        val c = MaskState(
            listOf(
                radial(id = "c", cx = 0.4f, rx = 0.15f, ry = 0.15f, feather = 1f),
                radial(id = "d", cx = 0.6f, rx = 0.15f, ry = 0.15f, feather = 1f),
            )
        )
        // x=0.5: q_a = 0.1/0.15 = 2/3 -> smoothstep(0,1,2/3) = 0.7407;
        // both masks contribute 1-0.7407 = 0.2593; the union is
        // 1 - (1-0.2593)^2 = 0.4516 (NOT 0.5186, the naive sum).
        val expected = 1f - (1f - 0.25925926f) * (1f - 0.25925926f)
        assertEquals(expected, c.coverageAt(0.5f, 0.5f), 1e-4f)
    }

    @Test
    fun `stacked deltas clamp to the knob ranges`() {
        // Four +2 EV masks stacked at full weight: +8 clamps to +5 (the
        // editor's exposure slider range).
        val masks = (0 until 4).map { i ->
            radial(id = "m$i", cx = 0.5f, rx = 0.5f, ry = 0.5f, delta = MaskGradeDelta(exposureEv = 2f))
        }
        val s = MaskState(masks)
        assertEquals(5f, s.deltaAt(0.5f, 0.5f).exposureEv, 0f)
    }

    @Test
    fun `disabled masks do not render but keep their own weight`() {
        val m = radial(enabled = false, delta = ev)
        // The state skips it entirely...
        val s = MaskState(listOf(m))
        assertEquals(0f, s.coverageAt(0.5f, 0.5f), 0f)
        assertEquals(MaskGradeDelta(), s.deltaAt(0.5f, 0.5f))
        // ...but the mask's own weight is still meaningful for the UI
        // (the shape still draws, grayed).
        assertEquals(1f, m.weightAt(0.5f, 0.5f), 0f)
    }

    // ---- NaN / size / count rejections ----

    @Test
    fun `NaN and infinite parameters are rejected`() {
        val bad = listOf(
            { MaskShape.Radial(Float.NaN, 0.5f, 0.25f, 0.25f) },
            { MaskShape.Radial(0.5f, Float.POSITIVE_INFINITY, 0.25f, 0.25f) },
            { MaskShape.Linear(0.5f, 0f, Float.NaN, 1f) },
            { MaskK("m", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f), feather = Float.NaN) },
            { MaskK("m", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f), feather = 1.5f) },
            { MaskK("m", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f), feather = -0.1f) },
            { MaskGradeDelta(exposureEv = Float.NaN) },
            { MaskGradeDelta(highlights = Float.POSITIVE_INFINITY) },
            { MaskGradeDelta(tempShiftK = Float.NaN) },
            { MaskGradeDelta(exposureEv = 6f) },
            { MaskGradeDelta(saturation = -101f) },
            { MaskGradeDelta(tempShiftK = 6000f) },
        )
        for (make in bad) {
            try {
                make()
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `degenerate shapes are rejected`() {
        // Zero/negative radii: not an ellipse.
        try {
            MaskShape.Radial(0.5f, 0.5f, 0f, 0.25f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            MaskShape.Radial(0.5f, 0.5f, 0.25f, -0.25f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // A linear axis whose start equals its end: no direction.
        try {
            MaskShape.Linear(0.3f, 0.4f, 0.3f, 0.4f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `blank and duplicate ids are rejected`() {
        try {
            MaskK("", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            MaskK("  ", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            MaskState(listOf(radial(id = "dup"), radial(id = "dup")))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `the mask count is capped`() {
        val nine = (0 until MaskState.MAX_MASKS + 1).map { i ->
            radial(id = "m$i", delta = ev)
        }
        try {
            MaskState(nine)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at most"))
        }
        // Exactly at the cap is fine.
        val eight = (0 until MaskState.MAX_MASKS).map { i ->
            radial(id = "m$i", delta = ev)
        }
        assertEquals(MaskState.MAX_MASKS, MaskState(eight).masks.size)
    }

    @Test
    fun `off-frame centers are allowed (the corner vignette)`() {
        // Source-normalized parameters may sit outside 0..1: a corner
        // vignette needs an off-frame center.
        val m = radial(id = "v", cx = -0.2f, cy = -0.2f, rx = 0.6f, ry = 0.6f, delta = ev)
        assertEquals(1f, m.weightAt(0f, 0f), 1e-3f)
        assertEquals(0f, m.weightAt(1f, 1f), 1e-3f)
    }

    // ---- the serialization round-trip ----

    @Test
    fun `renderJson then parseJson is value-equal`() {
        val s = MaskState(
            listOf(
                MaskK(
                    id = "sky",
                    shape = MaskShape.Linear(0.1f, 0f, 0.9f, 0.35f),
                    feather = 0.35f,
                    invert = true,
                    enabled = true,
                    delta = MaskGradeDelta(exposureEv = -0.8f, tempShiftK = -1200f),
                ),
                MaskK(
                    id = "face",
                    shape = MaskShape.Radial(0.42f, 0.61f, 0.18f, 0.24f, angleDeg = 12.5f),
                    feather = 0.7f,
                    invert = false,
                    enabled = false,
                    delta = MaskGradeDelta(
                        highlights = -30f,
                        shadows = 25f,
                        saturation = 10f,
                        tint = 5f,
                    ),
                ),
                radial(id = "plain"),
            )
        )
        val json = s.renderJson()
        val back = MaskState.parseJson(json)
        assertEquals(s, back)
        // Re-rendering what parsed back is byte-identical (one schema,
        // one parse path — the preset/undo contract).
        assertEquals(json, back.renderJson())
    }

    @Test
    fun `the empty list renders an empty array and parses back`() {
        assertEquals("[]", MaskState().renderJson())
        assertEquals(MaskState(), MaskState.parseJson("[]"))
    }

    @Test
    fun `neutral fields are omitted and defaults parse back`() {
        // A feather-0, non-inverted, enabled mask with one knob renders
        // only the fields that differ from the defaults.
        val s = MaskState(listOf(radial(id = "a", delta = ev)))
        val json = s.renderJson()
        assertFalse(json.contains("feather"))
        assertFalse(json.contains("invert"))
        assertFalse(json.contains("enabled"))
        assertFalse(json.contains("angle"))
        assertTrue(json.contains("exposureEv"))
        assertEquals(s, MaskState.parseJson(json))
    }

    @Test
    fun `the parser is tolerant to unknown shapes and bad entries`() {
        // A future build's shape: skipped, the rest of the list loads.
        val json = """
            [
              {"id":"ok","shape":"radial","cx":0.5,"cy":0.5,"rx":0.25,"ry":0.25,
               "exposureEv":1.0},
              {"id":"future","shape":"lasso","points":[[0,0],[1,1]]},
              {"id":"bad","shape":"radial","cx":0.5,"cy":0.5,"rx":0.0,"ry":0.25},
              {"noid":"x"},
              {"id":"badrange","shape":"radial","cx":0.5,"cy":0.5,"rx":0.25,
               "ry":0.25,"exposureEv":99.0}
            ]
        """.trimIndent()
        val s = MaskState.parseJson(json)
        assertEquals(1, s.masks.size)
        assertEquals("ok", s.masks[0].id)
        assertEquals(1f, s.masks[0].delta.exposureEv, 0f)
    }

    @Test
    fun `the parser truncates past the cap and keeps the first duplicates`() {
        val entries = (0 until MaskState.MAX_MASKS + 2).joinToString(",") { i ->
            "{\"id\":\"m$i\",\"shape\":\"radial\"," +
                "\"cx\":0.5,\"cy\":0.5,\"rx\":0.25,\"ry\":0.25,\"exposureEv\":1.0}"
        }
        val s = MaskState.parseJson("[$entries]")
        assertEquals(MaskState.MAX_MASKS, s.masks.size)
        assertEquals("m0", s.masks[0].id)
        // A duplicate id: the FIRST entry wins, the load never crashes.
        val dup = "[{\"id\":\"x\",\"shape\":\"radial\",\"cx\":0.5,\"cy\":0.5," +
            "\"rx\":0.25,\"ry\":0.25},{\"id\":\"x\",\"shape\":\"linear\"," +
            "\"x0\":0,\"y0\":0,\"x1\":0,\"y1\":1}]"
        val sd = MaskState.parseJson(dup)
        assertEquals(1, sd.masks.size)
        assertTrue(sd.masks[0].shape is MaskShape.Radial)
    }

    @Test
    fun `structural JSON errors throw`() {
        for (bad in listOf(
            "{", "[", "[{]", "not json", "{\"a\":}", "[[]", "[{]", "\"lonely\""
        )) {
            try {
                MaskState.parseJson(bad)
                org.junit.Assert.fail("expected IllegalArgumentException for \"$bad\"")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        // A non-object array element is skipped, not thrown.
        assertEquals(MaskState(), MaskState.parseJson("[1,2]"))
    }

    @Test
    fun `string escapes round-trip`() {
        val s = MaskState(
            listOf(
                MaskK(
                    id = "quote\"back\\slash",
                    shape = MaskShape.Linear(0f, 0f, 0f, 1f),
                    delta = ev,
                )
            )
        )
        assertEquals(s, MaskState.parseJson(s.renderJson()))
    }

    // ---- the cache-key stability ----

    @Test
    fun `the neutral list signs empty and equals a mask-free grade`() {
        assertEquals("", MaskState().signature())
        assertEquals("", MaskState(listOf(radial(enabled = false, delta = ev))).signature())
        assertEquals(
            "",
            MaskState(listOf(radial(delta = MaskGradeDelta()))).signature(),
        )
        // A disabled AND a neutral-delta stack: still "".
        assertEquals(
            "",
            MaskState(
                listOf(
                    radial(id = "a", enabled = false, delta = ev),
                    radial(id = "b", delta = MaskGradeDelta()),
                )
            ).signature(),
        )
    }

    @Test
    fun `the signature is stable across equal values and order`() {
        val a = MaskState(
            listOf(
                MaskK("a", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f), feather = 0.3f,
                    invert = true, delta = MaskGradeDelta(exposureEv = 1f, shadows = 20f)),
                MaskK("b", MaskShape.Linear(0f, 0f, 1f, 1f), feather = 0.5f,
                    delta = MaskGradeDelta(tempShiftK = -800f)),
            )
        )
        // Same masks, opposite order, different ids (ids do not sign).
        val b = MaskState(
            listOf(
                MaskK("zz", MaskShape.Linear(0f, 0f, 1f, 1f), feather = 0.5f,
                    delta = MaskGradeDelta(tempShiftK = -800f)),
                MaskK("yy", MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f), feather = 0.3f,
                    invert = true, delta = MaskGradeDelta(exposureEv = 1f, shadows = 20f)),
            )
        )
        assertEquals(a.signature(), b.signature())
        // Deterministic across calls.
        assertEquals(a.signature(), a.signature())
    }

    @Test
    fun `the signature changes when any rendering field changes`() {
        val base = MaskState(listOf(radial(id = "m", delta = ev)))
        fun sig(mutate: (MaskK) -> MaskK) =
            MaskState(listOf(mutate(radial(id = "m", delta = ev)))).signature()
        val baseSig = base.signature()
        // Shape params.
        assertNotEquals(baseSig, sig { it.copy(shape = MaskShape.Radial(0.6f, 0.5f, 0.25f, 0.25f)) })
        assertNotEquals(baseSig, sig { it.copy(shape = MaskShape.Radial(0.5f, 0.5f, 0.3f, 0.25f)) })
        assertNotEquals(baseSig, sig { it.copy(shape = MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f, 45f)) })
        assertNotEquals(baseSig, sig { it.copy(shape = MaskShape.Linear(0f, 0f, 1f, 1f)) })
        // Feather / invert / delta.
        assertNotEquals(baseSig, sig { it.copy(feather = 0.4f) })
        assertNotEquals(baseSig, sig { it.copy(invert = true) })
        assertNotEquals(baseSig, sig { it.copy(delta = MaskGradeDelta(exposureEv = 2f)) })
        assertNotEquals(baseSig, sig { it.copy(delta = MaskGradeDelta(exposureEv = 1f, tint = 1f)) })
        // Enabled false = the mask stops rendering: the signature goes
        // empty (the render is the neutral again).
        assertEquals("", sig { it.copy(enabled = false) })
        // The id alone does NOT change the render: same signature.
        assertEquals(baseSig, sig { it.copy(id = "renamed") })
    }

    @Test
    fun `duplicate rendering masks sign twice (they stack)`() {
        val one = MaskState(listOf(radial(id = "a", delta = ev)))
        val two = MaskState(listOf(radial(id = "a", delta = ev), radial(id = "b", delta = ev)))
        assertNotEquals(one.signature(), two.signature())
        assertTrue(two.signature().contains(";"))
    }

    // ---- the delta composition (applyTo) ----

    @Test
    fun `applyTo composes global plus weight times delta with clamps`() {
        val d = MaskGradeDelta(exposureEv = 1f, highlights = -20f, tempShiftK = -1000f)
        val g = GradeK(exposureEv = 0.5f, highlights = 10f, tempK = 6500f)
        val out = d.applyTo(g, weight = 0.5f)
        assertEquals(1.0f, out.exposureEv, 1e-6f)
        assertEquals(0f, out.highlights, 1e-6f)
        assertEquals(6000f, out.tempK, 1e-6f)
        // Weight 0: the exact identity.
        assertEquals(g, d.applyTo(g, weight = 0f))
        // The temp clamp holds the core's 2000..50000 domain at full
        // weight (a negative shift from 4800 K cannot go below 2000).
        val cool = GradeK(tempK = 4800f)
        assertEquals(3800f, d.applyTo(cool, weight = 1f).tempK, 0f)
        val nearFloor = GradeK(tempK = 2500f)
        assertEquals(2000f, d.applyTo(nearFloor, weight = 1f).tempK, 0f)
    }

    // ---- the coordinate contracts ----

    @Test
    fun `the identity transform maps display to source unchanged`() {
        val t = MaskViewTransform()
        val s = t.sourceFromDisplay(0.25f, 0.75f)
        assertEquals(0.25f, s.x, 1e-6f)
        assertEquals(0.75f, s.y, 1e-6f)
        val d = t.displayFromSource(0.25f, 0.75f)
        assertEquals(0.25f, d.x, 1e-6f)
        assertEquals(0.75f, d.y, 1e-6f)
    }

    @Test
    fun `zoom and pan invert exactly (the ZoomPan relation)`() {
        // b = 0.5 + pan + z * (c - 0.5): at zoom 2 with pan -0.5/0.5
        // (both at panBound (z-1)/2), the box's top-left shows the
        // content's (0.5, 0.0) — panX -0.5 slides the view right so the
        // content's midline sits at the box's left edge, panY +0.5 pins
        // the content's top to the box's top. Forward check:
        // bx = 0.5 - 0.5 + 2*(0.5-0.5) = 0, by = 0.5 + 0.5 + 2*(0-0.5) = 0.
        val t = MaskViewTransform(zoom = 2f, panX = -0.5f, panY = 0.5f)
        val s = t.sourceFromDisplay(0f, 0f)
        assertEquals(0.5f, s.x, 1e-6f)
        assertEquals(0f, s.y, 1e-6f)
        // And the inverse returns the box point.
        val d = t.displayFromSource(s.x, s.y)
        assertEquals(0f, d.x, 1e-5f)
        assertEquals(0f, d.y, 1e-5f)
        // With pan at its bound the box center shows (0.75, 0.25):
        // cx = (0.5 - 0.5 + 0.5)/2 + 0.5, cy = (0.5 - 0.5 - 0.5)/2 + 0.5.
        // Only at pan 0 does the box center show the content center.
        val c = t.sourceFromDisplay(0.5f, 0.5f)
        assertEquals(0.75f, c.x, 1e-6f)
        assertEquals(0.25f, c.y, 1e-6f)
        // Zoom alone (pan 0) at zoom 2: the box's left edge shows the
        // content's 0.25 — the doubled content spills equally.
        val z = MaskViewTransform(zoom = 2f)
        assertEquals(0.25f, z.sourceFromDisplay(0f, 0.5f).x, 1e-6f)
        assertEquals(0.75f, z.sourceFromDisplay(1f, 0.5f).x, 1e-6f)
    }

    @Test
    fun `the crop rect maps the displayed content into the frame`() {
        // The displayed content IS the crop: with a right-half crop the
        // box's left edge shows the frame's x=0.5.
        val t = MaskViewTransform(cropX = 0.5f, cropW = 0.5f)
        val s = t.sourceFromDisplay(0f, 0.5f)
        assertEquals(0.5f, s.x, 1e-6f)
        assertEquals(0.5f, s.y, 1e-6f)
        val s2 = t.sourceFromDisplay(1f, 0.5f)
        assertEquals(1f, s2.x, 1e-6f)
        // Round-trip.
        val d = t.displayFromSource(0.75f, 0.5f)
        assertEquals(0.5f, d.x, 1e-6f)
        assertEquals(0.5f, d.y, 1e-6f)
    }

    @Test
    fun `quarter turns swap axes per the mapRect convention`() {
        // effectiveDeg = 90 (a CW-turned portrait frame): the box's
        // top-left shows the source's bottom-left. The point analog of
        // CropState.mapRect's 90 case (x,y) -> (y, 1-x).
        val t = MaskViewTransform(effectiveDeg = 90)
        val s = t.sourceFromDisplay(0f, 0f)
        assertEquals(0f, s.x, 1e-6f)
        assertEquals(1f, s.y, 1e-6f)
        // The box's top-right shows the source's top-left.
        val s2 = t.sourceFromDisplay(1f, 0f)
        assertEquals(0f, s2.x, 1e-6f)
        assertEquals(0f, s2.y, 1e-6f)
        // 180 mirrors both axes; 270 is the other swap.
        val h = MaskViewTransform(effectiveDeg = 180)
        val sh = h.sourceFromDisplay(0.25f, 0.75f)
        assertEquals(0.75f, sh.x, 1e-6f)
        assertEquals(0.25f, sh.y, 1e-6f)
        val q = MaskViewTransform(effectiveDeg = 270)
        val sq = q.sourceFromDisplay(0f, 0f)
        assertEquals(1f, sq.x, 1e-6f)
        assertEquals(0f, sq.y, 1e-6f)
    }

    @Test
    fun `the full chain round-trips in both directions`() {
        val t = MaskViewTransform(
            zoom = 2.5f,
            panX = 0.3f,
            panY = -0.2f,
            cropX = 0.1f,
            cropY = 0.2f,
            cropW = 0.6f,
            cropH = 0.5f,
            effectiveDeg = 90,
        )
        for (p in listOf(
            0.5f to 0.5f, 0f to 0f, 1f to 1f, 0.3f to 0.7f, 0.9f to 0.1f
        )) {
            val s = t.sourceFromDisplay(p.first, p.second)
            val d = t.displayFromSource(s.x, s.y)
            assertEquals(p.first, d.x, 1e-4f)
            assertEquals(p.second, d.y, 1e-4f)
            // And the other direction.
            val d2 = t.displayFromSource(s.x, s.y)
            val s2 = t.sourceFromDisplay(d2.x, d2.y)
            assertEquals(s.x, s2.x, 1e-4f)
            assertEquals(s.y, s2.y, 1e-4f)
        }
    }

    @Test
    fun `invalid view transforms are rejected`() {
        val shape = MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f)
        // The crop must stay inside the frame with positive size.
        for (bad in listOf(
            { MaskViewTransform(cropX = -0.1f) },
            { MaskViewTransform(cropY = -0.1f) },
            { MaskViewTransform(cropX = 0.6f, cropW = 0.6f) },
            { MaskViewTransform(cropY = 0.6f, cropH = 0.6f) },
            { MaskViewTransform(cropW = 0f) },
            { MaskViewTransform(cropH = -0.5f) },
            { MaskViewTransform(zoom = 0f) },
            { MaskViewTransform(zoom = -1f) },
            { MaskViewTransform(zoom = Float.NaN) },
            { MaskViewTransform(panX = Float.POSITIVE_INFINITY) },
            { MaskViewTransform(cropX = Float.NaN) },
        )) {
            try {
                bad()
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        // A degenerate-looking but valid crop: zero-area is rejected,
        // but a hairline crop is not (the UI clamps sizes elsewhere).
        try {
            MaskViewTransform(cropW = 0f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // The shape parameter is unused here; keep it referenced.
        assertTrue(shape.rampAt(0.5f, 0.5f) == 0f)
    }

    // ---- the grid helper ----

    @Test
    fun `the coverage grid samples cell centers row-major`() {
        // One hard radial disk of radius 0.25 at the center on a 4x4
        // grid: only the center-adjacent cells whose centers lie inside
        // the disk are covered.
        val s = MaskState(listOf(radial(delta = ev)))
        val g = s.coverageGrid(4, 4)
        assertEquals(16, g.size)
        // Cell centers: x,y in {0.125, 0.375, 0.625, 0.875}. The four
        // inner cells' centers are at distance sqrt(2)*0.125 = 0.177
        // < 0.25 -> 1; the edge-mid cells 0.375 > 0.25 -> 0.
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val cx = (x + 0.5f) / 4f
                val cy = (y + 0.5f) / 4f
                val dx = cx - 0.5f
                val dy = cy - 0.5f
                val inside = dx * dx + dy * dy <= 0.25f * 0.25f
                assertEquals(if (inside) 1f else 0f, g[y * 4 + x], 1e-6f)
            }
        }
        // Row-major order: g[0] is the top-left cell.
        assertEquals(0f, g[0], 0f)
        assertEquals(1f, g[1 * 4 + 1], 0f)
        // Bad steps rejected.
        for (bad in listOf(0 to 4, 4 to 0, -1 to 4, 4 to -1)) {
            try {
                s.coverageGrid(bad.first, bad.second)
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ---- the radial ramp math (the GPU reference) ----

    @Test
    fun `the radial ramp normalizes the tilted ellipse frame`() {
        // A 90-deg tilt turns the ellipse's long axis onto y: the ramp
        // at (cx + r, cy) uses ry, and at (cx, cy + r) uses rx.
        val t = MaskShape.Radial(0.5f, 0.5f, rx = 0.3f, ry = 0.1f, angleDeg = 90f)
        // On the x-axis: q = 0.25 / 0.1 = 2.5.
        assertEquals(2.5f, t.rampAt(0.75f, 0.5f), 1e-4f)
        // On the y-axis: q = 0.15 / 0.3 = 0.5.
        assertEquals(0.5f, t.rampAt(0.5f, 0.65f), 1e-4f)
        // The boundary (q = 1) sits at ry on x and rx on y.
        assertEquals(1f, t.rampAt(0.6f, 0.5f), 1e-4f)
        assertEquals(1f, t.rampAt(0.5f, 0.8f), 1e-4f)
        // The tilt trig is periodic: 90 + 360 behaves identically.
        val t2 = MaskShape.Radial(0.5f, 0.5f, rx = 0.3f, ry = 0.1f, angleDeg = 450f)
        assertEquals(t.rampAt(0.62f, 0.57f), t2.rampAt(0.62f, 0.57f), 1e-6f)
    }
}
