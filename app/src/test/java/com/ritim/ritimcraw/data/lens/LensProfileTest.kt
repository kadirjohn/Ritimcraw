package com.ritim.ritimcraw.data.lens

import com.ritim.ritimcraw.data.metadata.ContainerIdentityFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LensProfile schema + parser tests (B7 / GY-06, pure model):
 *  - round-trip incl. the omit-when-default byte-stability the stores pin;
 *  - forward tolerance (absent keys -> defaults, unknown keys ignored,
 *    unknown ordinals defaulted);
 *  - corrupt degradation (malformed -> IllegalArgumentException for the
 *    store's catch; broken/invalid items skipped, never fabricated);
 *  - the validation rejections (NaN/negative focal, zero dims, mode
 *    uniqueness, out-of-range mode, zoom < 1) with the stage/cause/
 *    recovery shape;
 *  - the identity profile's neutrality (empty distortion = the pinhole
 *    the warp assumes today; a no-op by construction);
 *  - the binding-key derivation from the REAL container fields
 *    (ContainerIdentityFacts + the mcrawCamera fallback chain).
 */
class LensProfileTest {

    // A realistic VALID profile: the owner's Xiaomi 15 Ultra main lens
    // (the fixture's container values) with a real calibration.
    private fun xiaomiMain(): LensProfile = LensProfile(
        name = "Xiaomi 15 Ultra — main",
        source = LensProfile.SOURCE_APP_CALIBRATED,
        identity = LensIdentity(
            camera = "Xiaomi 15 Ultra",
            focal35mm = 23.950197219848633,
            aperture = 1.6299999952316284,
        ),
        intrinsics = LensIntrinsics(
            focalLengthMm = 9.37,
            focalLengthPx = 3352.4,
            sensorWidthPx = 4096,
            sensorHeightPx = 3072,
            principalPointX = 2040.5,
            principalPointY = 1544.8,
        ),
        distortion = LensDistortion(k1 = -0.285, k2 = 0.11, p1 = 0.0004, p2 = -0.0002),
        modes = listOf(
            LensStabMode(mode = 0, zoomLimit = 1.15),
            LensStabMode(mode = 2, zoomLimit = 1.25),
        ),
    )

    // ---- round-trip ----

    @Test
    fun render_parse_round_trips() {
        val p = xiaomiMain()
        val reloaded = LensProfile.parseProfileText(LensProfile.renderProfile(p))
        assertEquals(p, reloaded)
    }

    @Test
    fun minimal_profile_round_trips_byte_stable() {
        // The omit-when-default pin: a profile carrying only the required
        // keys renders to this exact byte string, and re-rendering the
        // parsed value is byte-identical (the stores' stability pin).
        val p = LensProfile(
            identity = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95),
            intrinsics = LensIntrinsics(
                focalLengthMm = 9.37, focalLengthPx = 3352.4,
                sensorWidthPx = 4096, sensorHeightPx = 3072,
            ),
        )
        val expected =
            "{\"version\":1,\"identity\":{\"camera\":\"Xiaomi 15 Ultra\",\"focal35mm\":23.95}," +
                "\"intrinsics\":{\"focalLengthMm\":9.37,\"focalLengthPx\":3352.4," +
                "\"sensorWidthPx\":4096,\"sensorHeightPx\":3072}}"
        val rendered = LensProfile.renderProfile(p)
        assertEquals(expected, rendered)
        // No optional key leaks into the bytes.
        assertTrue(!rendered.contains("\"name\""))
        assertTrue(!rendered.contains("\"source\""))
        assertTrue(!rendered.contains("\"aperture\""))
        assertTrue(!rendered.contains("\"distortion\""))
        assertTrue(!rendered.contains("\"modes\""))
        assertTrue(!rendered.contains("\"principalPoint"))
        // And the round-trip re-renders identically.
        val reloaded = LensProfile.parseProfileText(rendered)!!
        assertEquals(p, reloaded)
        assertEquals(rendered, LensProfile.renderProfile(reloaded))
    }

    @Test
    fun document_round_trips() {
        // Both profiles VALID: parseProfiles skips items failing
        // validation (an invalid calibration never reaches the warp),
        // so the canonical document carries only validated profiles.
        val tele = LensProfile(
            identity = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 70.0, aperture = 1.8),
            intrinsics = LensIntrinsics(
                focalLengthMm = 22.5, focalLengthPx = 6116.6,
                sensorWidthPx = 4096, sensorHeightPx = 3072,
            ),
        )
        val profiles = listOf(xiaomiMain(), tele)
        val text = LensProfile.renderProfiles(profiles)
        assertEquals(profiles, LensProfile.parseProfiles(text))
        // The canonical document shape.
        assertTrue(text.startsWith("{\"version\":1,\"profiles\":["))
    }

    // ---- forward tolerance ----

    @Test
    fun absent_keys_fall_back_to_defaults() {
        // A pre-schema profile (the old-format precedent): only the keys
        // the first version knew. Absent keys -> the SAME defaults a
        // new LensProfile() has — the round-trip equals the data class.
        val legacy = LensProfile(
            identity = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95),
            intrinsics = LensIntrinsics(
                focalLengthMm = 9.37, focalLengthPx = 3352.4,
                sensorWidthPx = 4096, sensorHeightPx = 3072,
            ),
        )
        val text = LensProfile.renderProfiles(listOf(legacy))
        val parsed = LensProfile.parseProfiles(text)
        assertEquals(listOf(legacy), parsed)
    }

    @Test
    fun unknown_keys_are_ignored() {
        // A future schema adds keys; this parser must never wedge.
        val text = "{" +
            "\"version\":99," +
            "\"futureTop\":{\"nested\":[1,2]}," +
            "\"profiles\":[{" +
            "\"version\":1,\"futureField\":\"x\"," +
            "\"identity\":{\"camera\":\"Xiaomi 15 Ultra\",\"focal35mm\":23.95," +
            "\"futureIdentityField\":true}," +
            "\"intrinsics\":{\"focalLengthMm\":9.37,\"focalLengthPx\":3352.4," +
            "\"sensorWidthPx\":4096,\"sensorHeightPx\":3072,\"futureIntrinsicsField\":null}," +
            "\"futureModes\":\"anything\"" +
            "}]}"
        val parsed = LensProfile.parseProfiles(text)
        assertEquals(1, parsed.size)
        assertEquals(
            LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95),
            parsed[0].identity,
        )
        assertEquals(9.37, parsed[0].intrinsics.focalLengthMm, 0.0)
        assertTrue(parsed[0].distortion.isIdentity())
        assertTrue(parsed[0].modes.isEmpty())
    }

    @Test
    fun per_item_version_defaults_and_unknown_source_defaults() {
        // An item without "version" parses with the current schema
        // version; an unknown source ordinal falls back to USER (the
        // LutInterp unknown-ordinal precedent: a future extension can
        // never wedge an older reader — and never claims authority).
        val text = "{" +
            "\"profiles\":[{" +
            "\"identity\":{\"camera\":\"X\",\"focal35mm\":23.95}," +
            "\"intrinsics\":{\"focalLengthMm\":9.37,\"focalLengthPx\":3352.4," +
            "\"sensorWidthPx\":4096,\"sensorHeightPx\":3072}," +
            "\"source\":7" +
            "}]}"
        val parsed = LensProfile.parseProfiles(text)
        assertEquals(1, parsed.size)
        assertEquals(LensProfile.SCHEMA_VERSION, parsed[0].version)
        assertEquals(LensProfile.SOURCE_USER, parsed[0].source)
    }

    // ---- corrupt degradation ----

    @Test
    fun malformed_document_throws_for_the_store_catch() {
        // Not valid JSON: the store's catch (GradeStore.load precedent)
        // degrades to emptyList.
        listOf(
            "",
            "{",
            "{\"profiles\":",
            "{\"profiles\":[}",
            "{\"profiles\":[{}",
            "not json",
            "{\"profiles\":[]} trailing",
        ).forEach { text ->
            var threw = false
            try {
                LensProfile.parseProfiles(text)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("expected IllegalArgumentException for: $text", threw)
        }
    }

    @Test
    fun root_without_profiles_key_is_empty_never_null() {
        // A root object from another tool / a foreign section: no
        // "profiles" key -> empty, not an error (backward tolerance).
        assertEquals(emptyList<LensProfile>(), LensProfile.parseProfiles("{\"version\":1}"))
        assertEquals(emptyList<LensProfile>(), LensProfile.parseProfiles("{}"))
    }

    @Test
    fun broken_and_invalid_items_are_skipped() {
        // A non-object item and an item failing validation are skipped —
        // an invalid calibration never reaches the warp; the valid one
        // survives in its place (mapNotNull, the parseGyros precedent).
        val valid = xiaomiMain()
        val text = "{" +
            "\"profiles\":[" +
            "\"a string\"," +
            "42," +
            LensProfile.renderProfile(LensProfile(identity = LensIdentity(camera = "X", focal35mm = 23.95))) + // zero dims -> invalid
            "," +
            LensProfile.renderProfile(valid) +
            "]}"
        val parsed = LensProfile.parseProfiles(text)
        assertEquals(listOf(valid), parsed)
    }

    // ---- validation ----

    @Test
    fun valid_profile_has_no_errors() {
        assertTrue(LensProfile.validate(xiaomiMain()).isEmpty())
    }

    @Test
    fun identity_validation_rejections() {
        val base = xiaomiMain()
        val blankCamera = base.copy(identity = base.identity.copy(camera = " "))
        val nanFocal = base.copy(identity = base.identity.copy(focal35mm = Double.NaN))
        val negativeFocal = base.copy(identity = base.identity.copy(focal35mm = -23.95))
        val negativeAperture = base.copy(identity = base.identity.copy(aperture = -1.6))
        assertTrue(LensProfile.validate(blankCamera).any { it.stage == "identity" })
        assertTrue(LensProfile.validate(nanFocal).any { it.stage == "identity" })
        assertTrue(LensProfile.validate(negativeFocal).any { it.stage == "identity" })
        assertTrue(LensProfile.validate(negativeAperture).any { it.stage == "identity" })
    }

    @Test
    fun intrinsics_validation_rejections() {
        val base = xiaomiMain()
        val nanFocalPx = base.copy(intrinsics = base.intrinsics.copy(focalLengthPx = Double.NaN))
        val negativeFocalMm = base.copy(intrinsics = base.intrinsics.copy(focalLengthMm = -9.37))
        val zeroDims = base.copy(
            intrinsics = base.intrinsics.copy(sensorWidthPx = 0, sensorHeightPx = 0),
        )
        val nanPrincipal = base.copy(
            intrinsics = base.intrinsics.copy(principalPointX = Double.POSITIVE_INFINITY),
        )
        val outsidePrincipal = base.copy(
            intrinsics = base.intrinsics.copy(principalPointY = 99999.0),
        )
        assertTrue(LensProfile.validate(nanFocalPx).any { it.stage == "intrinsics" })
        assertTrue(LensProfile.validate(negativeFocalMm).any { it.stage == "intrinsics" })
        assertTrue(LensProfile.validate(zeroDims).any { it.stage == "intrinsics" })
        assertTrue(LensProfile.validate(nanPrincipal).any { it.stage == "intrinsics" })
        assertTrue(LensProfile.validate(outsidePrincipal).any { it.stage == "intrinsics" })
    }

    @Test
    fun distortion_validation_rejects_nan_but_accepts_identity() {
        val base = xiaomiMain()
        val nan = base.copy(distortion = base.distortion.copy(k2 = Double.NaN))
        assertTrue(LensProfile.validate(nan).any { it.stage == "distortion" })
        // The EMPTY set is VALID — it IS the pinhole identity, never an error.
        val identity = base.copy(distortion = LensDistortion())
        assertTrue(LensProfile.validate(identity).isEmpty())
    }

    @Test
    fun modes_validation_rejections() {
        val base = xiaomiMain()
        val unknownMode = base.copy(modes = listOf(LensStabMode(mode = 3, zoomLimit = 1.1)))
        val duplicateMode = base.copy(
            modes = listOf(
                LensStabMode(mode = 0, zoomLimit = 1.1),
                LensStabMode(mode = 0, zoomLimit = 1.2),
            ),
        )
        val magnifyingZoom = base.copy(modes = listOf(LensStabMode(mode = 0, zoomLimit = 0.9)))
        assertTrue(LensProfile.validate(unknownMode).any { it.stage == "modes" })
        assertTrue(LensProfile.validate(duplicateMode).any { it.stage == "modes" })
        assertTrue(LensProfile.validate(magnifyingZoom).any { it.stage == "modes" })
    }

    @Test
    fun provenance_validation_rejection() {
        val base = xiaomiMain()
        val bad = base.copy(source = 9)
        val errs = LensProfile.validate(bad)
        assertTrue(errs.any { it.stage == "profile" })
    }

    @Test
    fun errors_carry_stage_cause_recovery() {
        // DESIGN.md Error states: name the stage, the cause, and the
        // recovery — never a bare "invalid".
        val errs = LensProfile.validate(
            LensProfile(identity = LensIdentity(camera = "", focal35mm = 0.0)),
        )
        assertTrue(errs.isNotEmpty())
        errs.forEach { e ->
            assertTrue(e.stage.isNotBlank())
            assertTrue(e.cause.isNotBlank())
            assertTrue(e.recovery.isNotBlank())
            assertTrue(e.message().startsWith("Lens profile rejected (stage: "))
            assertTrue(e.message().contains(" — recovery: "))
        }
    }

    // ---- the identity profile's neutrality ----

    @Test
    fun identity_profile_is_the_pinhole_the_warp_assumes() {
        // All-zero distortion IS the identity: the warp's current model
        // (pure pinhole, no distortion terms anywhere) is exactly this
        // profile — an uncalibrated lens changes nothing.
        val d = LensDistortion()
        assertTrue(d.isIdentity())
        // And it renders omit-when-zero: the bytes carry no distortion
        // object at all, so a pre-B8 reader never sees a phantom block.
        val p = LensProfile(
            identity = LensIdentity(camera = "X", focal35mm = 23.95),
            intrinsics = LensIntrinsics(
                focalLengthMm = 9.37, focalLengthPx = 3352.4,
                sensorWidthPx = 4096, sensorHeightPx = 3072,
            ),
            distortion = LensDistortion(k1 = 0.0, p1 = 0.0),
        )
        assertTrue(!LensProfile.renderProfile(p).contains("\"distortion\""))
        // A k1-only calibration renders ONLY k1 (omit-when-zero per term).
        val k1only = p.copy(distortion = LensDistortion(k1 = -0.3))
        val rendered = LensProfile.renderProfile(k1only)
        assertTrue(rendered.contains("\"distortion\":{\"k1\":-0.3}"))
    }

    @Test
    fun empty_mode_list_is_no_per_lens_caps() {
        // An empty mode list = the grade's defaults apply untouched.
        val p = xiaomiMain().copy(modes = emptyList())
        assertTrue(p.modes.isEmpty())
        assertTrue(!LensProfile.renderProfile(p).contains("\"modes\""))
    }

    // ---- binding-key derivation ----

    @Test
    fun binding_key_rounds_off_container_float_noise() {
        // The container's floats carry digit noise (23.950197219848633
        // vs 23.95); one lens must not split into two keys.
        val a = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.950197219848633, aperture = 1.6299999952316284)
        val b = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95, aperture = 1.6)
        assertEquals(a.bindingKey(), b.bindingKey())
        assertEquals("Xiaomi 15 Ultra|24.0mm|f/1.6", a.bindingKey())
    }

    @Test
    fun binding_key_omits_absent_aperture() {
        val noAperture = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95)
        assertEquals("Xiaomi 15 Ultra|24.0mm", noAperture.bindingKey())
    }

    @Test
    fun binding_key_separates_the_device_lenses() {
        // The Xiaomi 15 Ultra's main/tele/periscope sit far apart in
        // 35mm-equivalent terms — the one container field that
        // disambiguates the LENS on a multi-lens device.
        val main = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 23.95, aperture = 1.6)
        val tele = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 70.0, aperture = 1.8)
        val periscope = LensIdentity(camera = "Xiaomi 15 Ultra", focal35mm = 115.0, aperture = 2.5)
        assertTrue(main.bindingKey() != tele.bindingKey())
        assertTrue(tele.bindingKey() != periscope.bindingKey())
        assertTrue(main.bindingKey() != periscope.bindingKey())
    }

    @Test
    fun identity_derives_from_the_real_container_facts() {
        // The owner's real Xiaomi: uniqueCameraModel is EMPTY — the
        // chain falls to deviceModel. Both the profile author and the
        // matching clip derive through identityFromContainerFacts, so
        // both sides fold to the same key.
        val facts = ContainerIdentityFacts(
            uniqueCameraModel = "",
            deviceModel = "Xiaomi 15 Ultra",
            manufacturer = "Xiaomi",
            buildModel = "15 Ultra",
            apertureMm = 1.6299999952316284,
            focalLengthMm = 23.950197219848633,
        )
        val derived = LensProfile.identityFromContainerFacts(facts)!!
        assertEquals("Xiaomi 15 Ultra", derived.camera)
        assertEquals(23.950197219848633, derived.focal35mm, 0.0)
        assertEquals(1.6299999952316284, derived.aperture, 0.0)
        assertEquals("Xiaomi 15 Ultra|24.0mm|f/1.6", derived.bindingKey())
        // The hand-authored profile binds to the same key.
        assertEquals(
            xiaomiMain().identity.bindingKey(),
            derived.bindingKey(),
        )
    }

    @Test
    fun identity_chain_falls_back_like_mcraw_camera() {
        // MetadataBuilder.mcrawCamera's exact fallback chain.
        // uniqueCameraModel wins when present.
        assertEquals(
            "Full Camera Name",
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(
                    uniqueCameraModel = "Full Camera Name",
                    deviceModel = "Xiaomi 15 Ultra",
                    focalLengthMm = 23.95,
                ),
            )!!.camera,
        )
        // manufacturer + buildModel fold to "man bm".
        assertEquals(
            "Xiaomi 15 Ultra",
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(
                    manufacturer = "Xiaomi",
                    buildModel = "15 Ultra",
                    focalLengthMm = 23.95,
                ),
            )!!.camera,
        )
        // buildModel alone.
        assertEquals(
            "15 Ultra",
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(buildModel = "15 Ultra", focalLengthMm = 23.95),
            )!!.camera,
        )
        // manufacturer alone.
        assertEquals(
            "Xiaomi",
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(manufacturer = "Xiaomi", focalLengthMm = 23.95),
            )!!.camera,
        )
    }

    @Test
    fun identity_never_fabricates_when_facts_are_absent() {
        // No camera chain -> null; no focal -> null; a non-positive or
        // NaN focal -> null. NEVER a fabricated key (the no-fabrication
        // rule; 0 = absent per the RawMeta convention).
        assertNull(LensProfile.identityFromContainerFacts(ContainerIdentityFacts()))
        assertNull(
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(deviceModel = "Xiaomi 15 Ultra"),
            ),
        )
        assertNull(
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(
                    deviceModel = "Xiaomi 15 Ultra",
                    focalLengthMm = 0.0,
                ),
            ),
        )
        assertNull(
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(
                    deviceModel = "Xiaomi 15 Ultra",
                    focalLengthMm = -1.0,
                ),
            ),
        )
        assertNull(
            LensProfile.identityFromContainerFacts(
                ContainerIdentityFacts(
                    deviceModel = "Xiaomi 15 Ultra",
                    focalLengthMm = Double.NaN,
                ),
            ),
        )
    }

    @Test
    fun absent_or_nonpositive_aperture_is_carried_as_absent() {
        val derived = LensProfile.identityFromContainerFacts(
            ContainerIdentityFacts(
                deviceModel = "Xiaomi 15 Ultra",
                apertureMm = 0.0,
                focalLengthMm = 23.95,
            ),
        )!!
        assertEquals(0.0, derived.aperture, 0.0)
        assertEquals("Xiaomi 15 Ultra|24.0mm", derived.bindingKey())
    }

    // ---- the intrinsics' tier scaling (the plan's two-tier consumption) ----

    @Test
    fun pixel_focal_scales_to_the_plan_tier() {
        // The plan computes at the preview tier AND full resolution
        // (Warp.h: the plan's dims are the warped buffer's dims); the
        // stored focal scales proportionally. Identity at the
        // calibration width; 0 for non-positive inputs (the core's
        // refusal convention — never a fabricated focal).
        val i = LensIntrinsics(
            focalLengthMm = 9.37, focalLengthPx = 3352.4,
            sensorWidthPx = 4096, sensorHeightPx = 3072,
        )
        assertEquals(3352.4, i.focalLengthPxAt(4096), 1e-9)
        assertEquals(3352.4 * 1280.0 / 4096.0, i.focalLengthPxAt(1280), 1e-9)
        assertEquals(0.0, i.focalLengthPxAt(0), 0.0)
        assertEquals(0.0, i.focalLengthPxAt(-5), 0.0)
        assertEquals(0.0, LensIntrinsics().focalLengthPxAt(4096), 0.0)
    }
}
