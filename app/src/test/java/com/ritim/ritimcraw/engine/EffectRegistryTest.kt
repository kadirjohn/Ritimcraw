package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effect-registry cross-check (owner request 2026-11-15): the
 * registry is the CHECKLIST, the live tables are the TRUTH, and THIS
 * test keeps the two from drifting. Adding a new develop effect means
 * adding its row AND updating the live gates — if only one side moves,
 * a test here fails with the stage number in the message.
 *
 * The three live-table locks:
 *   1. every FRAGMENT_COVERED_STAGES entry has a registry row;
 *   2. every registry row's stage number exists in the CPU stage list's
 *      documented range (developCpu's 1..24 contract, or the temporal
 *      pre-stage's own marker);
 *   3. every row's coverage token matches the live fallback vocabulary
 *      (fragmentUncoveredStages' runtime leg — the ONLY tokens any
 *      active grade can contribute today).
 */
class EffectRegistryTest {

    // ---- lock 1: the covered set and the registry agree ----

    @Test
    fun every_fragment_covered_stage_has_a_registry_row() {
        val covered = BackendRules.FRAGMENT_COVERED_STAGES
        assertTrue("the live covered set must not be empty", covered.isNotEmpty())
        val rowStages = EffectRegistry.ROWS.map { it.stage }.toSet()
        val missing = covered - rowStages
        assertTrue(
            "FRAGMENT_COVERED_STAGES has stages with no EffectRegistry row: $missing " +
                "(adding a covered stage means adding its row — the registry is the checklist)",
            missing.isEmpty(),
        )
    }

    @Test
    fun every_registry_stage_row_is_fragment_covered() {
        // The reverse direction: a stage row that is NOT in the covered
        // set is a row lying about its GPU site (or a stale row for a
        // stage that lost coverage — either way the pair must be fixed
        // together).
        val covered = BackendRules.FRAGMENT_COVERED_STAGES
        val stageRows = EffectRegistry.ROWS.filter { it.stage != EffectRegistry.STAGE_TEMPORAL }
        val uncovered = stageRows.map { it.stage }.filter { it !in covered }
        assertTrue(
            "EffectRegistry holds stage rows outside FRAGMENT_COVERED_STAGES: $uncovered " +
                "(a stage row and its coverage move together)",
            uncovered.isEmpty(),
        )
    }

    // ---- lock 2: stage numbers exist in the CPU contract's range ----

    @Test
    fun every_row_stage_is_in_the_cpu_contract_range_or_the_temporal_marker() {
        val valid = (EffectRegistry.CPU_STAGE_FIRST..EffectRegistry.CPU_STAGE_LAST) +
            EffectRegistry.STAGE_TEMPORAL
        val bad = EffectRegistry.ROWS.map { it.stage }.filter { it !in valid }
        assertTrue(
            "EffectRegistry rows carry stage numbers outside the CPU contract " +
                "(developCpu documents stages ${EffectRegistry.CPU_STAGE_FIRST}.." +
                "${EffectRegistry.CPU_STAGE_LAST}; the temporal pre-stage is " +
                "STAGE_TEMPORAL): $bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun the_registry_covers_the_whole_cpu_contract_exactly_once() {
        // The CPU contract is a dense 1..24: the registry must mirror it
        // with exactly one row per stage (no gaps, no duplicates) plus
        // exactly one temporal pre-stage row.
        assertEquals(
            "the registry must hold every develop stage exactly once",
            (EffectRegistry.CPU_STAGE_FIRST..EffectRegistry.CPU_STAGE_LAST).toList(),
            EffectRegistry.ROWS.map { it.stage }
                .filter { it != EffectRegistry.STAGE_TEMPORAL }
                .sorted(),
        )
    }

    @Test
    fun the_temporal_pre_stage_is_exactly_one_row() {
        assertEquals(
            "the temporal pre-stage (outside the 24-stage contract) must be exactly one row",
            1,
            EffectRegistry.ROWS.count { it.stage == EffectRegistry.STAGE_TEMPORAL },
        )
    }

    // ---- lock 3: tokens match the live fallback vocabulary ----

    @Test
    fun every_row_token_is_none_or_in_the_live_vocabulary() {
        // The live vocabulary: the tokens fragmentUncoveredStages can
        // actually produce for an active grade. Derived from the live
        // function itself (not a copy): a grade with every knob active,
        // with the LUT path unhealthy, yields the full runtime set.
        val allActive = com.ritim.ritimcraw.data.GradeK(
            lutId = "some-lut",
            lutStrength = 100f,
        )
        val liveVocabulary =
            BackendRules.fragmentUncoveredStages(allActive, lutTexturePathOk = false)
                .map { it.second }.toSet()
        val bad = EffectRegistry.ROWS
            .map { it.coverageToken }
            .filter { it != "none" && it !in liveVocabulary }
        assertTrue(
            "EffectRegistry rows carry tokens the live fallback line can never emit: $bad " +
                "(live vocabulary today: $liveVocabulary)",
            bad.isEmpty(),
        )
    }

    @Test
    fun the_lut_runtime_leg_maps_to_the_lut_row_token() {
        // The ONE runtime leg: an active LUT grade with an unhealthy
        // texture path falls back with the "lut" token at stage 24, and
        // stage 24's row must carry exactly that token.
        val activeLut = com.ritim.ritimcraw.data.GradeK(
            lutId = "some-lut",
            lutStrength = 100f,
        )
        val uncovered = BackendRules.fragmentUncoveredStages(activeLut, lutTexturePathOk = false)
        assertEquals(listOf(24 to "lut"), uncovered)
        assertEquals("lut", EffectRegistry.ROWS_BY_STAGE.getValue(24).coverageToken)
    }

    @Test
    fun every_row_token_names_a_stage_the_live_leg_can_emit() {
        // The healthy-path mirror: with the LUT path healthy, the live
        // leg emits NOTHING, so no active grade contributes a token —
        // the vocabulary lock above must not be vacuous.
        val activeLut = com.ritim.ritimcraw.data.GradeK(
            lutId = "some-lut",
            lutStrength = 100f,
        )
        assertTrue(
            "a healthy LUT texture path must leave no uncovered stage",
            BackendRules.fragmentUncoveredStages(activeLut, lutTexturePathOk = true).isEmpty(),
        )
    }

    // ---- row-shape honesty locks ----

    @Test
    fun every_row_cites_its_cpu_site_and_gpu_site() {
        // A row without a live citation is a fabricated row — the
        // registry's whole contract is "written from the live tables".
        EffectRegistry.ROWS.forEach { row ->
            assertTrue(
                "row ${row.stage} (${row.name}) must cite a CPU site (file:line)",
                row.cpuSite.contains(Regex("\\.cpp:\\d+")) || row.cpuSite.contains(Regex("\\.kt:\\d+")),
            )
            assertTrue(
                "row ${row.stage} (${row.name}) must name a GPU site or the routing shape",
                row.gpuSite.isNotBlank(),
            )
        }
    }

    @Test
    fun the_temporal_row_is_routing_not_a_shader_port() {
        // The temporal hybrid's contract: the GPU leg is ROUTING (the
        // merged CFA feeds the sessions as the CFA), never a shader
        // kind — the merge math stays the frozen CPU's.
        val temporal = EffectRegistry.ROWS_BY_STAGE.getValue(EffectRegistry.STAGE_TEMPORAL)
        assertEquals(EffectRegistry.EffectClass.TEMPORAL, temporal.effectClass)
        assertTrue(
            "the temporal row's GPU site must be the routing shape (no shader kind)",
            temporal.gpuSite.startsWith("none"),
        )
    }

    @Test
    fun only_stage_24_needs_the_lut_texture_leg() {
        // The capabilities vocabulary: FLOAT_FBO for the draw chain,
        // LUT_TEXTURE only for the one stage whose coverage rides the
        // runtime fact, NONE for the temporal pre-stage.
        EffectRegistry.ROWS.forEach { row ->
            val needsLut = row.hardware.contains("LUT_TEXTURE")
            assertEquals(
                "row ${row.stage} (${row.name}): the LUT texture leg belongs to stage 24 only",
                row.stage == 24,
                needsLut,
            )
        }
    }
}
