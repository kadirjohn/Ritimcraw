package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RenderTrace tests, all JVM (audit M0, 2026-11-13): the pure line
 * formatting of the production render trace — the owner's A/B
 * instrument against the audit's host numbers
 * (17.5/61.8/62.4/107.9 ms at the 1280 tier). The line() function is
 * pure (the MetricsSummary precedent); the tests pin the field order,
 * the source-tier naming, the ACTUAL-dims field (the audit's 720 -> 640
 * stride finding), and the grade summary's per-state contents — the
 * exact legs of the owner's A/B run.
 */
class RenderTraceTest {

    private fun record(
        grade: GradeK = GradeK(),
        sequence: Long = 12L,
        frameIndex: Int = 34,
        renderClass: PreviewTiers.RenderClass = PreviewTiers.RenderClass.SETTLE,
        requestedMaxDim: Int = PreviewTiers.PAUSED_MAX_DIM,
        outW: Int = 1280,
        outH: Int = 720,
        renderMs: Long = 62L,
        temporalNrApplied: Boolean = false,
        stabilizationApplied: Boolean = false,
        servedByGpu: Boolean = false,
        sourceW: Int = 0,
        sourceH: Int = 0,
        developW: Int = 0,
        developH: Int = 0,
        surfaceW: Int = 0,
        surfaceH: Int = 0,
        statsEnabled: Boolean = true,
        decMs: Double = 0.0,
        cfaMs: Double = 0.0,
        upMs: Double = 0.0,
        devMs: Double = 0.0,
        presentMs: Double = 0.0,
        statsMs: Double = 0.0,
    ) = RenderTrace.Record(
        sequence, frameIndex, renderClass, requestedMaxDim,
        outW, outH, renderMs, grade, temporalNrApplied, stabilizationApplied,
        servedByGpu, sourceW, sourceH, developW, developH, surfaceW, surfaceH,
        statsEnabled, decMs, cfaMs, upMs, devMs, presentMs, statsMs,
    )

    // ---- the line: field order and naming ----

    @Test
    fun neutral_settle_line_has_the_stable_field_order() {
        // The owner greps these; the order is the format. A neutral
        // grade at the paused tier, the A/B baseline leg.
        assertEquals(
            "seq=12 frame=34 class=settle req=1280 out=1280x720 " +
                "backend=cpu ms=62 grade=neutral",
            RenderTrace.line(record()),
        )
    }

    @Test
    fun source_tier_reads_src_not_zero() {
        // maxDim 0 is the SOURCE follow-up; "req=0" would read as a
        // broken request. The truth is the recorded resolution.
        assertEquals(
            "seq=1 frame=0 class=settle req=src out=3840x2160 " +
                "backend=cpu ms=104 grade=neutral",
            RenderTrace.line(
                record(
                    sequence = 1L, frameIndex = 0, requestedMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                    outW = 3840, outH = 2160, renderMs = 104L,
                )
            ),
        )
    }

    @Test
    fun actual_dims_log_the_developed_truth_not_the_request() {
        // THE audit finding: a 720 request on a 3840x2160 clip develops
        // 640x360 (integer Bayer-cell strides) — the line must carry
        // the developed buffer's real dims next to the request.
        assertEquals(
            "seq=5 frame=9 class=playback req=720 out=640x360 " +
                "backend=cpu ms=21 grade=neutral",
            RenderTrace.line(
                record(
                    sequence = 5L, frameIndex = 9,
                    renderClass = PreviewTiers.RenderClass.PLAYBACK,
                    requestedMaxDim = PreviewTiers.PLAYBACK_MAX_DIM,
                    outW = 640, outH = 360, renderMs = 21L,
                )
            ),
        )
    }

    // ---- the grade summary: the A/B legs ----

    @Test
    fun gpu_served_frame_reads_backend_gles_and_the_measurement_block() {
        // M4 commit c + the trace-dims fix (2026-11-14): the GPU window
        // line carries backend=gles AND the measurement block after
        // grade= — out= is the published STATS pack's dims (NOT the
        // render resolution), so source=/develop=/surface= carry the real
        // dims and dec/cfa/up/dev/present/stats= the per-stage spans.
        // The exact 12MP playback shape the owner's diagnosis reads.
        assertEquals(
            "seq=12 frame=34 class=settle req=1280 out=256x144 " +
                "backend=gles ms=62 grade=neutral " +
                "source=4032x3024 develop=1280x960 surface=1080x810 " +
                "stats=256x144 dec=18.4ms cfa=2.7ms up=1.2ms dev=3.9ms " +
                "present=1.1ms stats=0.8ms",
            RenderTrace.line(
                record(
                    servedByGpu = true,
                    outW = 256, outH = 144,
                    sourceW = 4032, sourceH = 3024,
                    developW = 1280, developH = 960,
                    surfaceW = 1080, surfaceH = 810,
                    decMs = 18.4, cfaMs = 2.7, upMs = 1.2,
                    devMs = 3.9, presentMs = 1.1, statsMs = 0.8,
                )
            ),
        )
    }

    @Test
    fun gpu_served_with_stats_disabled_reads_stats_off() {
        // The A/B instrument: the toggle off skips draw 3 + the readback
        // entirely — the stats= dims field reads "off" (the ms field still
        // prints, 0.0 — the stage never ran, the honest zero), and the
        // published pack degraded to the 1x1 fallback shows in out=.
        assertEquals(
            "seq=12 frame=34 class=settle req=1280 out=1x1 " +
                "backend=gles ms=62 grade=neutral " +
                "source=4032x3024 develop=1280x960 surface=1080x810 " +
                "stats=off dec=18.4ms cfa=2.7ms up=1.2ms dev=3.9ms " +
                "present=1.1ms stats=0.0ms",
            RenderTrace.line(
                record(
                    servedByGpu = true,
                    outW = 1, outH = 1,
                    sourceW = 4032, sourceH = 3024,
                    developW = 1280, developH = 960,
                    surfaceW = 1080, surfaceH = 810,
                    statsEnabled = false,
                    decMs = 18.4, cfaMs = 2.7, upMs = 1.2,
                    devMs = 3.9, presentMs = 1.1, statsMs = 0.0,
                )
            ),
        )
    }

    @Test
    fun cpu_line_stays_byte_identical_with_measurement_fields_present() {
        // THE compat pin: the CPU path constructs the record with the
        // neutral defaults (and the CPU renderer never populates them), so
        // its line keeps the exact pre-fix shape — every existing grep
        // and baseline leg keeps working. Even a record that happens to
        // carry measurement values stays CPU-shaped unless servedByGpu.
        assertEquals(
            "seq=12 frame=34 class=settle req=1280 out=1280x720 " +
                "backend=cpu ms=62 grade=neutral",
            RenderTrace.line(
                record(
                    servedByGpu = false,
                    sourceW = 4032, sourceH = 3024,
                    decMs = 18.4, statsMs = 0.8,
                )
            ),
        )
    }

    @Test
    fun gpu_served_keeps_the_grade_summary_untouched() {
        // The backend verdict rides the line's own field; the grade legs
        // (the A/B's per-stage story) stay exactly what the CPU legs pin —
        // the measurement block appends AFTER grade=.
        assertEquals(
            "tonemap=agx source=0x0 develop=0x0 surface=0x0 stats=1280x720 " +
                "dec=0.0ms cfa=0.0ms up=0.0ms dev=0.0ms present=0.0ms stats=0.0ms",
            RenderTrace.line(record(grade = GradeK(tonemap = 2), servedByGpu = true))
                .substringAfter("grade="),
        )
    }


    @Test
    fun agx_leg_lists_tonemap_only() {
        // A/B leg 2: neutral grade -> AgX. The tonemap is the only
        // active stage, named for the curve family the UI shows.
        val line = RenderTrace.line(record(grade = GradeK(tonemap = 2)))
        assertTrue(line.endsWith("grade=tonemap=agx"))
        assertFalse(line.endsWith("grade=neutral"))
    }

    @Test
    fun managed_lut_leg_lists_tonemap_and_lut() {
        // A/B leg 3: AgX -> +managed LUT. Both stages active, pipeline
        // order (tonemap before lut), the lut id named.
        val line = RenderTrace.line(
            record(grade = GradeK(tonemap = 2, lutId = "kodak-2383"))
        )
        assertTrue(line.endsWith("grade=tonemap=agx lut=kodak-2383"))
    }

    @Test
    fun aces_names_the_aces_fit() {
        assertEquals(
            "seq=12 frame=34 class=settle req=1280 out=1280x720 " +
                "backend=cpu ms=62 grade=tonemap=aces",
            RenderTrace.line(record(grade = GradeK(tonemap = 1))),
        )
    }

    @Test
    fun agx_looks_four_and_five_still_read_agx() {
        // tonemap 2-5 are the AgX looks (Punchy/Golden/Greyscale/Base);
        // the trace names the family, the look dial stays in the UI.
        for (t in 2..5) {
            assertTrue(
                RenderTrace.line(record(grade = GradeK(tonemap = t))).contains("tonemap=agx")
            )
        }
    }

    @Test
    fun temporal_nr_lists_only_when_the_gate_applied_it() {
        // The M0 story lands here: the grade's tnr toggle is NOT the
        // line's truth — the gate's decision is. A SETTLE render with
        // tnr on at the paused tier shows tnr=<strength>; the same
        // grade on a PLAYBACK render (the gate says no) shows nothing —
        // the choppy-preview semantics, visible in the trace.
        val grade = GradeK(temporalNr = true, temporalStrength = 100f)
        val applied = RenderTrace.line(record(grade = grade, temporalNrApplied = true))
        assertTrue(applied.endsWith("grade=tnr=100"))
        val skipped = RenderTrace.line(
            record(
                grade = grade,
                renderClass = PreviewTiers.RenderClass.PLAYBACK,
                temporalNrApplied = false,
            )
        )
        assertTrue(skipped.endsWith("grade=neutral"))
    }

    @Test
    fun spatial_nr_lists_the_luma_chroma_pair() {
        val line = RenderTrace.line(
            record(grade = GradeK(noiseReduction = 40f, chromaDenoise = 25f))
        )
        assertTrue(line.endsWith("grade=nr=40/25"))
    }

    @Test
    fun stabilization_lists_only_when_a_gyro_log_ran_the_warp() {
        // stab needs BOTH the grade's toggle and an attached log; the
        // record carries the resolved truth, so an on-grade with no log
        // stays out of the line.
        val withLog = RenderTrace.line(record(stabilizationApplied = true))
        assertTrue(withLog.endsWith("grade=stab"))
        val noLog = RenderTrace.line(record(stabilizationApplied = false))
        assertFalse(noLog.contains("stab"))
    }

    @Test
    fun keep_toggles_name_what_turning_them_off_does() {
        // vign/hlrec default TRUE (the stage runs); only the OFF state
        // is a deviation worth reading in a trace line.
        val off = RenderTrace.line(
            record(grade = GradeK(keepVignetting = false, highlightReconstruction = false))
        )
        assertTrue(off.endsWith("grade=vign-off hlrec-off"))
        val on = RenderTrace.line(record())
        assertFalse(on.contains("vign"))
        assertFalse(on.contains("hlrec"))
    }

    @Test
    fun all_stages_together_stay_in_pipeline_order() {
        val line = RenderTrace.line(
            record(
                grade = GradeK(
                    keepVignetting = false, highlightReconstruction = false,
                    tonemap = 2, lutId = "teal-orange",
                    noiseReduction = 30f, chromaDenoise = 30f,
                    temporalNr = true, temporalStrength = 80f,
                ),
                temporalNrApplied = true,
                stabilizationApplied = true,
            )
        )
        assertEquals(
            "grade=vign-off hlrec-off tonemap=agx lut=teal-orange nr=30/30 tnr=80 stab",
            line.substring(line.indexOf("grade=")),
        )
    }

    @Test
    fun dials_round_to_whole_percents() {
        // 12.5 strength reads "13" — no decimal noise in a grep line.
        val line = RenderTrace.line(
            record(grade = GradeK(noiseReduction = 12.5f), temporalNrApplied = false)
        )
        assertTrue(line.endsWith("grade=nr=13/0"))
    }

    @Test
    fun enabled_follows_the_build_config_debug_gate() {
        // The PreviewMetrics precedent: release builds never log. On
        // the JVM test classpath BuildConfig.DEBUG is false — the gate
        // reads it live, so this pins the wiring, not the value.
        assertEquals(com.ritim.ritimcraw.BuildConfig.DEBUG, RenderTrace.enabled)
    }

    @Test
    fun gpu_grade_summary_carries_the_moved_slider_values() {
        // 2026-11-23 (GPU preview shows no change): the Kotlin-side half of
        // the before/after pair the native probe lines complete.
        val a = RenderTrace.gpuGradeSummary(GradeK(), 3, 1280, 7L)
        val b = RenderTrace.gpuGradeSummary(GradeK(highlights = -40f, exposureEv = 1.5f), 3, 1280, 8L)
        assertTrue(a.contains("grade[ev=0.000 hi=0.0 "))
        assertTrue(b.contains("grade[ev=1.500 hi=-40.0 "))
        assertTrue(b.startsWith("gpu grade seq=8 frame=3 maxDim=1280 "))
        assertTrue(a != b)
    }
}
