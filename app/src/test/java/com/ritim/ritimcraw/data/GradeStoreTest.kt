package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GradeStore tests (sprint plan Task 4):
 *  - grades_do_not_leak_between_clips: a grade set on clip A never appears on
 *    clip B, including after persist + reload (QA-05).
 */
class GradeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun grades_do_not_leak_between_clips() {
        val file = projectFile()
        val store = GradeStore(file)

        val clipA = "clip-a"
        val clipB = "clip-b"
        store.put(clipA, GradeK(exposureEv = 1f))
        store.put(clipB, GradeK(tempK = 3200f))

        // Reload from disk: a NEW store over the same file (simulating app
        // close + reopen), not the in-memory instance.
        val reloaded = GradeStore(file)
        assertEquals(1f, reloaded.get(clipA).exposureEv, 0f)
        assertEquals(5600f, reloaded.get(clipA).tempK, 0f) // A keeps default temp
        assertEquals(0f, reloaded.get(clipB).exposureEv, 0f) // B keeps default EV
        assertEquals(3200f, reloaded.get(clipB).tempK, 0f)
    }

    @Test
    fun put_persists_every_write() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(exposureEv = 0.5f))
        // Written immediately (no flush/close step).
        assertTrue(file.exists())
        assertEquals(0.5f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    @Test
    fun write_survives_sources_section_in_same_file() {
        // GradeStore and SourceRepository share project.json; a grade write
        // must not clobber the sources section (and vice versa).
        val file = projectFile()
        val sources = SourceRepository(FakeResolver(), file)
        val imported = sources.import("content://clip/1")

        val grades = GradeStore(file)
        grades.put(imported.id, GradeK(exposureEv = 2f))

        assertEquals(1, SourceRepository(FakeResolver(), file).all().size)
        assertEquals(2f, GradeStore(file).get(imported.id).exposureEv, 0f)
    }

    @Test
    fun write_survives_exports_section_in_same_file() {
        // ExportStore also writes project.json; a grade write must not
        // clobber the exports section (and an exports write keeps grades).
        val file = projectFile()
        val exports = ExportStore(file)
        exports.add(
            ExportJob(
                id = "job-1",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        GradeStore(file).put("clip-a", GradeK(tempK = 4300f))

        val reloaded = ExportStore(file).all()
        assertEquals(1, reloaded.size)
        assertEquals(Target.MP4, reloaded.single().target)
        assertEquals(4300f, GradeStore(file).get("clip-a").tempK, 0f)
    }

    @Test
    fun remove_deletes_grade() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(highlights = -20f))
        store.remove("c1")
        assertEquals(0f, GradeStore(file).get("c1").highlights, 0f)
    }

    @Test
    fun corrupt_store_falls_back_to_defaults() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        assertEquals(0f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    @Test
    fun old_format_file_loads_tone_controls_as_neutral() {
        // A project.json written by the pre-tone-controls build (schema 1,
        // only the four original fields). The six new fields are absent and
        // must parse as their neutral defaults — the old look is preserved
        // exactly, which is the owner's binding requirement for this package.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"legacy-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("legacy-clip")
        // The four original fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(-25f, g.highlights, 0f)
        assertEquals(4600f, g.tempK, 0f)
        assertEquals(3f, g.tint, 0f)
        // ...and the six tone/color fields are neutral.
        assertEquals(0f, g.saturation, 0f)
        assertEquals(0f, g.blackPoint, 0f)
        assertEquals(0f, g.whitePoint, 0f)
        assertEquals(0f, g.shadows, 0f)
        assertEquals(0f, g.midtones, 0f)
        assertEquals(0f, g.contrast, 0f)
    }

    @Test
    fun tone_control_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            saturation = 42f,
            blackPoint = -18f,
            whitePoint = 63f,
            shadows = -7f,
            midtones = 29f,
            contrast = -96f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
    }

    @Test
    fun raw_toggle_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            asShot = false,
            highlightReconstruction = false,
            keepVignetting = false,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
    }

    @Test
    fun old_format_file_defaults_raw_toggles_to_true() {
        // A project.json written by the pre-RAW-toggles build. The two stage
        // toggles are absent and must parse as true (pre-toggle behavior), so
        // an old project keeps its exact look — the binding requirement of
        // RAW-04/RAW-06. Here temp/tint are neutral, so asShot derives true.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"neutral-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":5600.0,"tint":0.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("neutral-clip")
        assertTrue(g.asShot) // neutral manual WB means as-shot WB
        assertTrue(g.highlightReconstruction)
        assertTrue(g.keepVignetting)
    }

    @Test
    fun old_format_file_derives_asshot_false_from_manual_wb() {
        // The pre-RAW-toggles build had no asShot field, but it DID have
        // manual temp/tint sliders that always ran. A grade saved with
        // manual WB must keep its exact old look, so the missing asShot
        // derives false (stages 14-15 keep running) rather than defaulting
        // to true and silently discarding the user's WB.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"manual-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("manual-clip")
        assertTrue(!g.asShot) // manual WB means manual WB wins
        assertTrue(g.highlightReconstruction)
        assertTrue(g.keepVignetting)
        assertEquals(4600f, g.tempK, 0f)
        assertEquals(3f, g.tint, 0f)
    }

    @Test
    fun old_format_file_loads_detail_and_tonemap_as_neutral() {
        // A project.json written by the pre-detail/tonemap build (COL-15/
        // COL-16). The three new fields are absent and must parse as their
        // neutral defaults (sharpness 0, detail 0, tonemap 0 = off) — the
        // native pipeline treats all three as no-ops, so the old look is
        // preserved exactly (the binding requirement of this package).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-detail-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-detail-clip")
        // The pre-existing fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(-25f, g.highlights, 0f)
        assertEquals(4600f, g.tempK, 0f)
        assertEquals(3f, g.tint, 0f)
        assertEquals(10f, g.saturation, 0f)
        assertEquals(8f, g.contrast, 0f)
        // ...and the three detail/tonemap fields are neutral.
        assertEquals(0f, g.sharpness, 0f)
        assertEquals(0f, g.detail, 0f)
        assertEquals(0, g.tonemap)
    }

    @Test
    fun detail_and_tonemap_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            sharpness = 35f,
            detail = -12f,
            tonemap = 2, // AgX
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
        assertEquals(35f, reloaded.sharpness, 0f)
        assertEquals(-12f, reloaded.detail, 0f)
        assertEquals(2, reloaded.tonemap)
    }

    @Test
    fun tonemap_int_values_round_trip_through_json() {
        // tonemap is an Int in the grade but travels through JSON: all three
        // choices (0 off, 1 ACES, 2 AgX) must survive a persist + reload
        // without float drift or silent clamping.
        val file = projectFile()
        GradeStore(file).put("off", GradeK(tonemap = 0))
        GradeStore(file).put("aces", GradeK(tonemap = 1))
        GradeStore(file).put("agx", GradeK(tonemap = 2))

        assertEquals(0, GradeStore(file).get("off").tonemap)
        assertEquals(1, GradeStore(file).get("aces").tonemap)
        assertEquals(2, GradeStore(file).get("agx").tonemap)
    }

    @Test
    fun lut_selection_and_strength_survive_persist_and_reload() {
        // COL-07: the selection (lutId) and the Strength are SEPARATE grade
        // fields, saved per clip with the rest of the grade.
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            lutId = "abc123def4567890",
            lutStrength = 37f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals("abc123def4567890", reloaded.lutId)
        assertEquals(37f, reloaded.lutStrength, 0f)
        assertEquals(grade, reloaded)
    }

    @Test
    fun old_format_file_loads_lut_as_neutral() {
        // A project.json written by the pre-LUT build (COL-07): the two new
        // fields are absent and must parse as their neutral defaults
        // (lutId null = no LUT, lutStrength 100 = full) — the native pipeline
        // bypasses stage 24 with lutId null, so the old look is preserved
        // exactly (the binding requirement of this package).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-lut-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true,"sharpness":15.0,"detail":-3.0,"tonemap":1}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-lut-clip")
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(15f, g.sharpness, 0f)
        assertEquals(1, g.tonemap)
        // The LUT fields are neutral: no selection, full strength.
        assertNull(g.lutId)
        assertEquals(100f, g.lutStrength, 0f)
    }

    @Test
    fun old_format_file_loads_temporal_nr_as_off() {
        // A project.json written by the pre-temporal-NR build (DN-05..DN-09).
        // The two new fields are absent and must parse as OFF/0 — temporal NR
        // never turns itself on behind the user's back, and since the stage
        // is a no-op when off, the old look is preserved exactly.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-temporal-clip":{"version":1,"exposureEv":0.8,"sharpness":10.0,"detail":-3.0,"tonemap":1,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-temporal-clip")
        assertEquals(false, g.temporalNr)
        assertEquals(0f, g.temporalStrength, 0f)
    }

    @Test
    fun json_null_lut_id_parses_as_no_selection() {
        // renderGrades writes explicit JSON null for lutId (not the field
        // being absent): both shapes must parse to null the same way.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"null-lut-clip":{"version":1,"exposureEv":0.0,"highlights":0.0,"tempK":5600.0,"tint":0.0,"saturation":0.0,"blackPoint":0.0,"whitePoint":0.0,"shadows":0.0,"midtones":0.0,"contrast":0.0,"asShot":true,"highlightReconstruction":true,"keepVignetting":true,"sharpness":0.0,"detail":0.0,"tonemap":0,"lutId":null,"lutStrength":100.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("null-lut-clip")
        assertNull(g.lutId)
        assertEquals(100f, g.lutStrength, 0f)
    }

    @Test
    fun denoise_percents_survive_persist_and_reload() {
        // DN-01..08: both denoise percents are ordinary grade fields — what
        // the user set survives a persist + reload exactly.
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            noiseReduction = 42f,
            chromaDenoise = 77f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(42f, reloaded.noiseReduction, 0f)
        assertEquals(77f, reloaded.chromaDenoise, 0f)
        assertEquals(grade, reloaded)
    }

    @Test
    fun old_format_file_loads_denoise_as_neutral() {
        // A project.json written by the pre-denoise build (DN-01..08): the
        // two new fields are absent and must parse as 0 (both native stages
        // 7/8 skipped) — the old look is preserved exactly, the binding
        // requirement of this feature.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-dn-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true,"sharpness":15.0,"detail":-3.0,"tonemap":1,"lutId":null,"lutStrength":100.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-dn-clip")
        // The pre-existing fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(15f, g.sharpness, 0f)
        assertNull(g.lutId)
        // ...and the two denoise fields are neutral (0 = stage skipped).
        assertEquals(0f, g.noiseReduction, 0f)
        assertEquals(0f, g.chromaDenoise, 0f)
    }

    @Test
    fun old_format_file_loads_temporal_as_neutral() {
        // A project.json written by the pre-temporal build (DN-05..09): the
        // two new fields are absent and must parse as off/0 — the old look
        // is preserved exactly.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-temporal-clip":{"version":1,"exposureEv":0.8,"sharpness":10.0,"detail":-3.0,"tonemap":1,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-temporal-clip")
        // Pre-existing fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(1, g.tonemap)
        // ...and temporal NR is off with neutral strength.
        assertEquals(false, g.temporalNr)
        assertEquals(0f, g.temporalStrength, 0f)
    }

    @Test
    fun temporal_nr_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            temporalNr = true,
            temporalStrength = 72.5f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
        assertEquals(true, reloaded.temporalNr)
        assertEquals(72.5f, reloaded.temporalStrength, 0f)
    }

    @Test
    fun old_format_file_loads_stabilization_as_off() {
        // A project.json written by the pre-stabilization build (gyro
        // Phase 5). The six new fields are absent and must parse as the OFF
        // defaults — stabilization never turns itself on behind the user's
        // back, and since the warp is a no-op when off, the old look is
        // preserved exactly (the binding rule of this package).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-stab-clip":{"version":1,"exposureEv":0.8,"sharpness":10.0,"detail":-3.0,"tonemap":1,"tempK":4600.0,"tint":3.0,"temporalNr":true,"temporalStrength":40.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-stab-clip")
        // Pre-existing fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(true, g.temporalNr)
        assertEquals(40f, g.temporalStrength, 0f)
        // ...and stabilization is off with its defaults.
        assertEquals(false, g.stabilization)
        assertEquals(0, g.stabilizationMode)
        assertEquals(50f, g.stabilizationStrength, 0f)
        assertEquals(1.1f, g.stabilizationZoomLimit, 0f)
        assertEquals(0f, g.stabilizationTrimMs, 0f)
        assertEquals(0f, g.stabilizationFocalMm, 0f)
    }

    @Test
    fun stabilization_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            stabilization = true,
            stabilizationMode = 2, // Horizon Lock
            stabilizationStrength = 85f,
            stabilizationZoomLimit = 1.25f,
            stabilizationTrimMs = -40f,
            stabilizationFocalMm = 24f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
        assertEquals(true, reloaded.stabilization)
        assertEquals(2, reloaded.stabilizationMode)
        assertEquals(85f, reloaded.stabilizationStrength, 0f)
        assertEquals(1.25f, reloaded.stabilizationZoomLimit, 0f)
        assertEquals(-40f, reloaded.stabilizationTrimMs, 0f)
        assertEquals(24f, reloaded.stabilizationFocalMm, 0f)
    }

    @Test
    fun off_grade_renders_without_stabilization_keys() {
        // Omit-when-off: a grade with stabilization off renders NONE of the
        // six keys, so the file this build writes for an off grade is
        // byte-identical to the pre-stabilization format (the binding
        // back-compat rule of this package). The sub-values being non-default
        // must NOT leak keys while the toggle is off.
        val rendered = GradeStore.renderGrade(
            GradeK(stabilization = false, stabilizationStrength = 99f, stabilizationTrimMs = 120f)
        )
        assertEquals(false, rendered.contains("stabilization"))
        // The on case renders all six, so the omission is the toggle's doing.
        val on = GradeStore.renderGrade(GradeK(stabilization = true))
        assertEquals(true, on.contains("\"stabilization\":true"))
        assertEquals(true, on.contains("\"stabilizationMode\":0"))
        assertEquals(true, on.contains("\"stabilizationStrength\":50.0"))
        assertEquals(true, on.contains("\"stabilizationZoomLimit\":1.1"))
        assertEquals(true, on.contains("\"stabilizationTrimMs\":0.0"))
        assertEquals(true, on.contains("\"stabilizationFocalMm\":0.0"))
    }

    @Test
    fun wheel_and_split_tone_survive_persist_and_reload() {
        // COL-13: the nine color-package fields are ordinary grade fields —
        // what the user set survives a persist + reload exactly.
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            wheelShadowX = 0.3f,
            wheelShadowY = -0.2f,
            wheelMidX = -0.8f,
            wheelMidY = 0.15f,
            wheelHighX = 0.6f,
            wheelHighY = 0.9f,
            splitShadowHue = 210f,
            splitHighlightHue = 45f,
            splitBalance = -30f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
        assertEquals(0.3f, reloaded.wheelShadowX, 0f)
        assertEquals(-0.2f, reloaded.wheelShadowY, 0f)
        assertEquals(-0.8f, reloaded.wheelMidX, 0f)
        assertEquals(0.15f, reloaded.wheelMidY, 0f)
        assertEquals(0.6f, reloaded.wheelHighX, 0f)
        assertEquals(0.9f, reloaded.wheelHighY, 0f)
        assertEquals(210f, reloaded.splitShadowHue, 0f)
        assertEquals(45f, reloaded.splitHighlightHue, 0f)
        assertEquals(-30f, reloaded.splitBalance, 0f)
    }

    @Test
    fun old_format_file_loads_color_package_as_neutral() {
        // A project.json written by the pre-color-wheel build (COL-13): the
        // nine new fields are absent and must parse as their neutral
        // defaults (offsets 0, hues -1 = off, balance 0) — the native
        // pipeline treats all nine as no-ops, so the old look is preserved
        // exactly (the binding requirement of this package).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"pre-wheel-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true,"sharpness":15.0,"detail":-3.0,"tonemap":1,"lutId":null,"lutStrength":100.0,"noiseReduction":0.0,"chromaDenoise":0.0,"temporalNr":false,"temporalStrength":0.0,"stabilization":false,"stabilizationMode":0,"stabilizationStrength":50.0,"stabilizationZoomLimit":1.1,"stabilizationTrimMs":0.0,"stabilizationFocalMm":0.0,"cropX":0,"cropY":0,"cropW":1.0,"cropH":1.0,"straightenDeg":0.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("pre-wheel-clip")
        // The pre-existing fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(15f, g.sharpness, 0f)
        assertEquals(1, g.tonemap)
        // ...and the nine color-package fields are neutral.
        assertEquals(0f, g.wheelShadowX, 0f)
        assertEquals(0f, g.wheelShadowY, 0f)
        assertEquals(0f, g.wheelMidX, 0f)
        assertEquals(0f, g.wheelMidY, 0f)
        assertEquals(0f, g.wheelHighX, 0f)
        assertEquals(0f, g.wheelHighY, 0f)
        assertEquals(-1f, g.splitShadowHue, 0f)
        assertEquals(-1f, g.splitHighlightHue, 0f)
        assertEquals(0f, g.splitBalance, 0f)
    }

    @Test
    fun neutral_color_package_renders_without_keys() {
        // Omit-when-neutral: a wheel-neutral grade renders NONE of the nine
        // keys, so the file this build writes for a neutral grade is
        // byte-identical to the pre-COL-13 format (the binding back-compat
        // rule of this package). Any one non-neutral field forces the full
        // nine-key package: the wheel and split tone travel as one unit
        // (they are one stage pair in the native pipeline).
        val rendered = GradeStore.renderGrade(GradeK())
        assertEquals(false, rendered.contains("wheelShadowX"))
        assertEquals(false, rendered.contains("splitShadowHue"))

        val wheelOnly = GradeStore.renderGrade(GradeK(wheelShadowX = 0.4f))
        assertEquals(true, wheelOnly.contains("\"wheelShadowX\":0.4"))
        // The package renders as one unit: the neutral split-tone keys ride
        // along (parsed back as neutral by the fallbacks).
        assertEquals(true, wheelOnly.contains("\"splitShadowHue\":-1.0"))
        assertEquals(true, wheelOnly.contains("\"splitBalance\":0.0"))
    }

    @Test
    fun split_balance_alone_is_not_neutral_for_rendering() {
        // The wheelSplitNeutral gate is all-nine-neutral, but balance alone
        // is a REAL setting, not a neutral one: with a hue on, balance
        // shifts the crossover, so a grade whose only non-neutral field is
        // the balance must still write its keys (and does — the gate
        // requires ALL nine neutral). This locks the gate's boundary.
        val balanceOnly = GradeStore.renderGrade(
            GradeK(splitShadowHue = 30f, splitBalance = 80f)
        )
        assertEquals(true, balanceOnly.contains("\"splitBalance\":80.0"))
        assertEquals(true, balanceOnly.contains("\"splitShadowHue\":30.0"))
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
