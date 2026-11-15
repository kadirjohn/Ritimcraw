package com.ritim.ritimcraw.data.lens

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson
import com.ritim.ritimcraw.data.metadata.ContainerIdentityFacts
import kotlin.math.roundToInt

/**
 * B7 lens profile — the GY-06 pure model: per-lens intrinsics +
 * distortion calibration as data, with its JSON schema and parser.
 * SCHEMA + PARSER ONLY (this package's boundary): nothing in the warp,
 * the plan resolver, the grade or the UI reads this yet — the warp
 * integration is a later wave, gated on a real calibration sample
 * ("Gerçek kalibrasyon örneği; warp entegrasyonu sonraki dalga").
 *
 * WHAT THE WARP ASSUMES TODAY (the cite — the gap GY-06 exists to close):
 *  - The plan (core Stabilization.h frameTransform) is a PURE PINHOLE,
 *    rotation-only homography H = K·D·R(q_rel)·D·K⁻¹ with
 *    K = [[f,0,cx],[0,-f,cy],[0,0,1]] (the -f absorbs the y-up camera
 *    frame against v-down pixels). The GPU WARP shader consumes the
 *    composite M = H⁻¹·S as one uniform mat3 with a single projective
 *    tap + bilinear (GlesShaderSources.WARP); the CPU warpFrame maps
 *    p_src = H⁻¹·(c + (p_out − c)/Z). Nothing anywhere models lens
 *    distortion — the lens the warp assumes is an ideal pinhole.
 *  - The pixel focal enters ONLY via focalLengthPxFromEquivalent
 *    (f_mm35, width) = width·f/36 — a STATED 35mm-equivalence
 *    assumption; every warp computed from it is flagged
 *    IntrinsicsAssumed, and the JNI plan resolver passes
 *    assumedFocal=true today, so every warp the app renders is
 *    assumed-intrinsics until a profile feeds it.
 *  - The principal point defaults to the buffer center
 *    (StabilizationParams.principalPoint {0,0} is the <= 0 sentinel ->
 *    w/2, h/2; the bridge hardcodes the center).
 *  - The container cannot close the gap itself: RawMeta.focalLengthMm
 *    is the 35mm-EQUIVALENT ("focalLengths"[0] =
 *    23.950197219848633 on the fixture), 0 = absent, and the MCRAW
 *    metadata carries NO sensor pitch, optical format or crop factor
 *    anywhere — the pixel focal is NOT derivable from the file.
 *
 * THE BINDING KEY (per-lens identity): derived from the REAL container
 * fields (Source.h ContainerIdentity, mirrored as
 * [ContainerIdentityFacts]) — never invented ones. The identity keys
 * name the DEVICE (a multi-lens phone shares them across lenses), so
 * the key = the camera fallback chain (MetadataBuilder.mcrawCamera's
 * exact chain: uniqueCameraModel — EMPTY on the owner's Xiaomi — then
 * deviceSpecificProfile.deviceModel, then "manufacturer buildModel",
 * then either alone) + the 35mm-equivalent focal rounded to 0.1 mm +
 * the aperture rounded to 0.1 when the container carries it:
 * "Xiaomi 15 Ultra|24.0mm|f/1.6". Why the focal: the Xiaomi 15 Ultra's
 * main/tele/periscope lenses sit far apart in 35mm-equivalent terms
 * (23/70/115 mm) — the one container field that disambiguates the LENS.
 * Why the aperture: the secondary tiebreaker, carried when present
 * (the fixture's "apertures"[0] = 1.6299999952316284), omitted when
 * absent (0 — the RawMeta convention). Why the rounding: the
 * container's floats carry digit noise (23.950197219848633 vs 23.95)
 * that must not split one lens into two keys, while real lenses sit
 * orders apart — no false merge. [identityFromContainerFacts] derives
 * the identity block from a clip's facts with the same chain, so both
 * sides (the profile author and the matching clip) fold to the same
 * key; it returns null when the camera chain or the focal is absent —
 * never a fabricated key.
 *
 * THE DISTORTION MODEL (the choice, stated): Brown-Conrady — even-order
 * radial k1/k2/k3 + tangential p1/p2, the OpenCV/Android lens-
 * calibration family, in normalized image coordinates (x = (u−cx)/f,
 * y = (v−cy)/f, r² = x²+y²):
 *    x_d = x·(1 + k1·r² + k2·r⁴ + k3·r⁶) + 2·p1·x·y + p2·(r² + 2·x²)
 *    y_d = y·(1 + k1·r² + k2·r⁴ + k3·r⁶) + p1·(r² + 2·y²) + 2·p2·x·y
 * (the division model: distortion maps the ideal pinhole point to the
 * observed point; undistortion inverts it). Why not the simpler
 * even-order-only radial: the warp's current math is the constraint and
 * it consumes NEITHER — a 3x3 homography is projective-linear,
 * distortion is r-dependent, so NO distortion model is consumable by
 * the existing plan/shader without a per-pixel mechanism (a
 * pre-undistort pass, or ray-level distortion in the resolver — the
 * integration wave's design decision; this schema stores the terms so
 * that wave is not write-limited). Given neither family is consumable
 * today, the schema stores the family the calibration produces and
 * the spec's named lenses need — GY-06: "tele/periskop ve geniş açıda
 * kalibrasyon kaliteyi belirler" — wide-angle modules carry real
 * tangential terms and need k2/k3; a k1-only schema would be
 * insufficient for exactly the lenses the spec calls out. All five
 * terms default 0 and render omit-when-zero: an EMPTY SET IS THE
 * IDENTITY — the pinhole the warp assumes today — so an uncalibrated
 * profile changes nothing (the no-fabrication rule).
 *
 * THE MODE/CROP MAPPING (onto the grade's existing fields, meaning
 * unchanged): [LensStabMode] carries GradeK.stabilizationMode's exact
 * ints — 0 = Smooth, 1 = Lock First Frame, 2 = Horizon Lock (the
 * StabilizationMode enum order minus Off) — and GradeK.
 * stabilizationZoomLimit's exact scale (>= 1; the core clamps
 * appliedZoom to min(required, this); Z = 1 = no crop, a warp may not
 * magnify the frame). [LensIdentity.focal35mm] maps onto GradeK.
 * stabilizationFocalMm (the 35mm-equivalent override, 0 = use the
 * container's value) — the profile is the honest source for the
 * override the user today must hand-enter. trimMs is DELIBERATELY NOT
 * carried: it is the per-clip gyro sync trim (trimOffsetMs over the
 * anchored estimate), a property of the RECORDING, not the lens — a
 * profile carrying it would change its meaning. strength is likewise
 * the user's taste dial, not a lens property. An empty mode list = no
 * per-lens crop caps — the grade's defaults apply untouched.
 *
 * PERSISTENCE (the stores' style, reusing their shared machinery — a
 * third hand-rolled parser would drift): fixed key order;
 * omit-when-default (a profile with only the required keys renders
 * byte-stable, and absent keys parse back to the same defaults);
 * forward-tolerant parse (unknown keys ignored, absent -> defaults,
 * per-item version defaulted); corrupt-input degradation (a malformed
 * document throws IllegalArgumentException for the store's catch ->
 * emptyList — the GradeStore.load precedent; a profile that is
 * structurally broken OR fails [validate] is SKIPPED — an invalid
 * calibration never reaches the warp). The canonical document is
 * {"version":1,"profiles":[...]} — parseProfiles reads root["profiles"]
 * from ANY root object, so the same parser serves a standalone file
 * AND a project.json section (the GyroStore.parseGyros precedent);
 * [renderProfilesArray] renders just the array for a section body.
 * JSON is hand-rolled because the offline Gradle cache has
 * kotlinx-serialization-core but not -json (the recorded deviation).
 *
 * INTEGRATOR'S DECISIONS (not made here, by design): the store's home
 * (standalone file vs a project.json section), duplicate-binding-key
 * policy (a collection with two profiles for one lens is ambiguous —
 * the store must reject or flag the duplicate), the warp's consumption
 * point (feeding StabilizationParams.focalLengthPx/principalPoint
 * retires the flagged-assumed conversion for lenses with a profile;
 * the distortion needs the per-pixel mechanism named above), and the
 * calibration data's provenance (the [source] field records which of
 * GY-06's three kinds a profile is).
 */
data class LensProfile(
    /** Schema version of this profile object; defaulted on parse, always rendered. */
    val version: Int = SCHEMA_VERSION,
    /** Human-readable label for the UI; blank = none (omit-when-blank). */
    val name: String = "",
    /**
     * Provenance (GY-06's three sources): [SOURCE_USER] hand-entered,
     * [SOURCE_APP_CALIBRATED] this app's own per-lens calibration,
     * [SOURCE_EMBEDDED] compatible embedded data. Defaults to USER —
     * the least authority: an unprovenanced profile never claims
     * embedded authority (the no-fabrication rule).
     */
    val source: Int = SOURCE_USER,
    /** The per-lens identity the profile binds to (the binding key's inputs). */
    val identity: LensIdentity = LensIdentity(),
    /** The intrinsics calibration (the warp's inputs, at the calibration dims). */
    val intrinsics: LensIntrinsics = LensIntrinsics(),
    /** The distortion calibration; the all-zero default IS the pinhole identity. */
    val distortion: LensDistortion = LensDistortion(),
    /** Per-mode crop caps in the grade's exact ints/scale; empty = no per-lens caps. */
    val modes: List<LensStabMode> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** Provenance: a user-authored profile (GY-06 "kullanıcı profili"). */
        const val SOURCE_USER = 0

        /** Provenance: this app's own per-lens calibration (GY-06 "kendi per-lens kalibrasyonu"). */
        const val SOURCE_APP_CALIBRATED = 1

        /** Provenance: compatible embedded data (GY-06 "uyumlu gömülü veri"). */
        const val SOURCE_EMBEDDED = 2

        // ---- writer ----

        /** The profiles array alone — the section body (the renderGyrosArray precedent). */
        fun renderProfilesArray(profiles: List<LensProfile>): String {
            val sb = StringBuilder()
            sb.append('[')
            profiles.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append(renderProfile(p))
            }
            sb.append(']')
            return sb.toString()
        }

        /** The canonical standalone document: {"version":1,"profiles":[...]}. */
        fun renderProfiles(profiles: List<LensProfile>): String =
            "{\"version\":$SCHEMA_VERSION,\"profiles\":${renderProfilesArray(profiles)}}"

        /**
         * One profile object — the single wire-format definition (the
         * renderGrade precedent: one render, one parse, no drift). Fixed
         * key order; omit-when-default for every optional key so a
         * minimal profile is byte-stable. Numbers render via
         * Double/Int.toString (locale-independent, the stores' style).
         * Renders what it is given: validate before rendering — an
         * unvalidated profile with NaN renders unparseable text (the
         * honest failure, never a silent fix).
         */
        fun renderProfile(p: LensProfile): String {
            val sb = StringBuilder()
            sb.append('{')
                .append("\"version\":").append(p.version)
            if (p.name.isNotBlank()) {
                sb.append(",\"name\":").append(ProjectJson.quote(p.name))
            }
            if (p.source != SOURCE_USER) {
                sb.append(",\"source\":").append(p.source)
            }
            sb.append(",\"identity\":{\"camera\":").append(ProjectJson.quote(p.identity.camera))
                .append(",\"focal35mm\":").append(p.identity.focal35mm)
            if (p.identity.aperture != 0.0) {
                sb.append(",\"aperture\":").append(p.identity.aperture)
            }
            sb.append('}')
            sb.append(",\"intrinsics\":{\"focalLengthMm\":").append(p.intrinsics.focalLengthMm)
                .append(",\"focalLengthPx\":").append(p.intrinsics.focalLengthPx)
                .append(",\"sensorWidthPx\":").append(p.intrinsics.sensorWidthPx)
                .append(",\"sensorHeightPx\":").append(p.intrinsics.sensorHeightPx)
            if (p.intrinsics.principalPointX != 0.0) {
                sb.append(",\"principalPointX\":").append(p.intrinsics.principalPointX)
            }
            if (p.intrinsics.principalPointY != 0.0) {
                sb.append(",\"principalPointY\":").append(p.intrinsics.principalPointY)
            }
            sb.append('}')
            if (!p.distortion.isIdentity()) {
                // Omit-when-zero per term: a k1-only calibration renders
                // {"k1":...} alone; the object renders only when at least
                // one term is carried.
                val carried = listOf(
                    "k1" to p.distortion.k1,
                    "k2" to p.distortion.k2,
                    "k3" to p.distortion.k3,
                    "p1" to p.distortion.p1,
                    "p2" to p.distortion.p2,
                ).filter { it.second != 0.0 }
                sb.append(",\"distortion\":{")
                carried.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(k).append("\":").append(v)
                }
                sb.append('}')
            }
            if (p.modes.isNotEmpty()) {
                sb.append(",\"modes\":[")
                p.modes.forEachIndexed { i, m ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"mode\":").append(m.mode)
                        .append(",\"zoomLimit\":").append(m.zoomLimit)
                        .append('}')
                }
                sb.append(']')
            }
            sb.append('}')
            return sb.toString()
        }

        // ---- reader ----

        /**
         * Parses the profiles out of a root JSON object — a standalone
         * document OR a project.json-style root (root["profiles"] is
         * read, every other key ignored — the parseGyros precedent).
         * Contract:
         *  - malformed JSON / root not an object / trailing content ->
         *    IllegalArgumentException (the store's catch degrades to
         *    emptyList — the GradeStore.load precedent);
         *  - "profiles" absent or not an array -> empty (backward/
         *    forward tolerance);
         *  - a structurally broken item (not an object) is skipped;
         *  - an item failing [validate] is SKIPPED — an invalid
         *    calibration never reaches the warp (the no-fabrication
         *    rule; the authoring path surfaces the reasons through
         *    validate itself).
         */
        fun parseProfiles(text: String): List<LensProfile> {
            val p = JsonParser(text)
            val root = p.parseValue()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["profiles"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                parseProfile(item)?.takeIf { validate(it).isEmpty() }
            }
        }

        /**
         * One profile object from its JSON text — the round-trip entry
         * (renderProfile -> parseProfileText). Malformed text / trailing
         * content -> IllegalArgumentException, the same contract as
         * [parseProfiles]; a non-object value -> null.
         */
        fun parseProfileText(text: String): LensProfile? {
            val p = JsonParser(text)
            val v = p.parseValue()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            return parseProfile(v)
        }

        /**
         * One profile from a parsed JSON value — TOLERANT, never
         * rejecting on values: unknown keys are ignored, absent keys
         * fall back to the defaults (a missing identity/intrinsics
         * block parses as the zero-filled block, which [validate]
         * then rejects — parse is tolerant, validation is the gate).
         * Null only for a value that is not an object at all.
         */
        fun parseProfile(v: Any?): LensProfile? {
            val m = v as? Map<*, *> ?: return null
            val im = m["identity"] as? Map<*, *>
            val inM = m["intrinsics"] as? Map<*, *>
            val dm = m["distortion"] as? Map<*, *>
            return LensProfile(
                version = (m["version"] as? Double)?.toInt() ?: SCHEMA_VERSION,
                name = m["name"] as? String ?: "",
                // Unknown ordinals fall back to USER (the LutInterp
                // precedent: a future source extension can never wedge
                // an older reader — and never claims authority).
                source = ((m["source"] as? Double)?.toInt() ?: SOURCE_USER)
                    .takeIf { it in SOURCE_USER..SOURCE_EMBEDDED } ?: SOURCE_USER,
                identity = LensIdentity(
                    camera = im?.get("camera") as? String ?: "",
                    focal35mm = im?.get("focal35mm") as? Double ?: 0.0,
                    aperture = im?.get("aperture") as? Double ?: 0.0,
                ),
                intrinsics = LensIntrinsics(
                    focalLengthMm = inM?.get("focalLengthMm") as? Double ?: 0.0,
                    focalLengthPx = inM?.get("focalLengthPx") as? Double ?: 0.0,
                    sensorWidthPx = (inM?.get("sensorWidthPx") as? Double)?.toInt() ?: 0,
                    sensorHeightPx = (inM?.get("sensorHeightPx") as? Double)?.toInt() ?: 0,
                    principalPointX = inM?.get("principalPointX") as? Double ?: 0.0,
                    principalPointY = inM?.get("principalPointY") as? Double ?: 0.0,
                ),
                distortion = if (dm == null) {
                    LensDistortion() // absent or JSON-null -> the identity
                } else {
                    LensDistortion(
                        k1 = dm["k1"] as? Double ?: 0.0,
                        k2 = dm["k2"] as? Double ?: 0.0,
                        k3 = dm["k3"] as? Double ?: 0.0,
                        p1 = dm["p1"] as? Double ?: 0.0,
                        p2 = dm["p2"] as? Double ?: 0.0,
                    )
                },
                modes = (m["modes"] as? List<*>)?.mapNotNull { e ->
                    val em = e as? Map<*, *> ?: return@mapNotNull null
                    val mode = (em["mode"] as? Double)?.toInt() ?: return@mapNotNull null
                    val zoomLimit = em["zoomLimit"] as? Double ?: return@mapNotNull null
                    LensStabMode(mode = mode, zoomLimit = zoomLimit)
                } ?: emptyList(),
            )
        }

        // ---- validation ----

        /**
         * The profile's validation rules, each as a stage/cause/
         * recovery [LensProfileError] (the PreviewErrors shape). Empty
         * list = valid. The rules:
         *  - identity: camera non-blank; the 35mm-equivalent focal
         *    finite and > 0; the aperture finite and >= 0 (0 = absent);
         *  - intrinsics: both focals finite and > 0 (NaN/negative
         *    focal rejected); the sensor pixel dims > 0 (zero dims
         *    rejected); the principal point finite, >= 0 (0 = the
         *    center sentinel) and inside the sensor dims;
         *  - distortion: every carried term finite — an EMPTY set is
         *    VALID (it IS the pinhole identity, never an error);
         *  - modes: each mode one of the grade's ints 0..2, each
         *    zoomLimit finite and >= 1 (a warp may not magnify), and
         *    the list's modes UNIQUE (one entry per mode);
         *  - profile: the provenance one of the three sources.
         */
        fun validate(p: LensProfile): List<LensProfileError> {
            val out = ArrayList<LensProfileError>()

            if (p.identity.camera.isBlank()) {
                out += LensProfileError(
                    stage = "identity",
                    cause = "the camera identity is blank",
                    recovery = "fill the camera chain value the profile binds to (the container's fallback chain)",
                )
            }
            if (!p.identity.focal35mm.isFinite() || p.identity.focal35mm <= 0.0) {
                out += LensProfileError(
                    stage = "identity",
                    cause = "the 35mm-equivalent focal is NaN, infinite or non-positive",
                    recovery = "enter the lens's 35mm-equivalent focal in mm (the container's focalLengths value)",
                )
            }
            if (!p.identity.aperture.isFinite() || p.identity.aperture < 0.0) {
                out += LensProfileError(
                    stage = "identity",
                    cause = "the aperture is NaN or negative",
                    recovery = "enter the lens's f-number, or 0 when the lens does not report one",
                )
            }

            if (!p.intrinsics.focalLengthMm.isFinite() || p.intrinsics.focalLengthMm <= 0.0) {
                out += LensProfileError(
                    stage = "intrinsics",
                    cause = "the focal length is NaN, infinite or non-positive",
                    recovery = "enter the lens's true focal length in mm from the calibration",
                )
            }
            if (!p.intrinsics.focalLengthPx.isFinite() || p.intrinsics.focalLengthPx <= 0.0) {
                out += LensProfileError(
                    stage = "intrinsics",
                    cause = "the pixel focal is NaN, infinite or non-positive",
                    recovery = "enter the pixel focal measured at the calibration dims",
                )
            }
            if (p.intrinsics.sensorWidthPx <= 0 || p.intrinsics.sensorHeightPx <= 0) {
                out += LensProfileError(
                    stage = "intrinsics",
                    cause = "the sensor pixel dims are zero",
                    recovery = "enter the pixel dims the intrinsics were calibrated against",
                )
            }
            if (!p.intrinsics.principalPointX.isFinite() || !p.intrinsics.principalPointY.isFinite() ||
                p.intrinsics.principalPointX < 0.0 || p.intrinsics.principalPointY < 0.0
            ) {
                out += LensProfileError(
                    stage = "intrinsics",
                    cause = "the principal point is NaN, infinite or negative",
                    recovery = "enter the principal point in pixels at the calibration dims, or 0 for the center",
                )
            }
            if ((p.intrinsics.principalPointX > 0.0 && p.intrinsics.principalPointX > p.intrinsics.sensorWidthPx) ||
                (p.intrinsics.principalPointY > 0.0 && p.intrinsics.principalPointY > p.intrinsics.sensorHeightPx)
            ) {
                out += LensProfileError(
                    stage = "intrinsics",
                    cause = "the principal point lies outside the sensor",
                    recovery = "enter a principal point within the calibration dims, or 0 for the center",
                )
            }

            val d = p.distortion
            if (!d.k1.isFinite() || !d.k2.isFinite() || !d.k3.isFinite() ||
                !d.p1.isFinite() || !d.p2.isFinite()
            ) {
                out += LensProfileError(
                    stage = "distortion",
                    cause = "the distortion coefficients contain NaN or infinity",
                    recovery = "re-run the calibration, or zero the terms — an empty set is the pinhole identity",
                )
            }

            val seen = HashSet<Int>()
            for (m in p.modes) {
                if (m.mode !in 0..2) {
                    out += LensProfileError(
                        stage = "modes",
                        cause = "unknown stabilization mode ${m.mode}",
                        recovery = "use 0 (Smooth), 1 (Lock First Frame) or 2 (Horizon Lock)",
                    )
                }
                if (!seen.add(m.mode)) {
                    out += LensProfileError(
                        stage = "modes",
                        cause = "two entries for mode ${m.mode}",
                        recovery = "keep one entry per mode — the list's modes must be unique",
                    )
                }
                if (!m.zoomLimit.isFinite() || m.zoomLimit < 1.0) {
                    out += LensProfileError(
                        stage = "modes",
                        cause = "the zoom limit for mode ${m.mode} is NaN or below 1",
                        recovery = "enter a limit >= 1 — a warp may not magnify the frame (the grade's zoomLimit scale)",
                    )
                }
            }

            if (p.source !in SOURCE_USER..SOURCE_EMBEDDED) {
                out += LensProfileError(
                    stage = "profile",
                    cause = "unknown provenance ${p.source}",
                    recovery = "use 0 (user), 1 (app-calibrated) or 2 (embedded)",
                )
            }
            return out
        }

        // ---- binding-key derivation ----

        /**
         * The identity block from a clip's REAL container facts
         * ([ContainerIdentityFacts] — the nativeContainerIdentity
         * payload plus RawMeta.focalLengthMm). The camera folds
         * through the same fallback chain as MetadataBuilder.
         * mcrawCamera; the focal must be present and positive (the
         * lens disambiguator — without it no honest binding exists);
         * the aperture rides along when present (0 = absent). Null
         * when the camera chain or the focal is absent — never a
         * fabricated identity. The profile author and the matching
         * clip both derive through here, so both sides fold to the
         * same [LensIdentity.bindingKey].
         */
        fun identityFromContainerFacts(facts: ContainerIdentityFacts): LensIdentity? {
            val camera = cameraChain(facts) ?: return null
            val focal = facts.focalLengthMm
            if (focal == null || !focal.isFinite() || focal <= 0.0) return null
            val aperture = facts.apertureMm
            return LensIdentity(
                camera = camera,
                focal35mm = focal,
                aperture = if (aperture != null && aperture.isFinite() && aperture > 0.0) aperture else 0.0,
            )
        }

        /** The container's camera fallback chain, mirroring MetadataBuilder.mcrawCamera. */
        private fun cameraChain(facts: ContainerIdentityFacts): String? {
            if (!facts.uniqueCameraModel.isNullOrBlank()) return facts.uniqueCameraModel
            if (!facts.deviceModel.isNullOrBlank()) return facts.deviceModel
            val man = facts.manufacturer
            val bm = facts.buildModel
            if (!man.isNullOrBlank() && !bm.isNullOrBlank()) return "$man $bm"
            if (!bm.isNullOrBlank()) return bm
            if (!man.isNullOrBlank()) return man
            return null
        }
    }
}

/**
 * The per-lens identity a profile binds to. All three fields are the
 * REAL container fields (the ContainerIdentity/RawMeta keys) — see the
 * package KDoc's binding-key section for the chain and the rounding.
 */
data class LensIdentity(
    /**
     * The camera chain string the key derives from:
     * uniqueCameraModel -> deviceModel -> "manufacturer buildModel" ->
     * buildModel -> manufacturer (mcrawCamera's exact chain). Blank =
     * unbound (validation rejects).
     */
    val camera: String = "",
    /** The 35mm-EQUIVALENT focal in mm (the container's focalLengths[0] scale); 0 = absent. */
    val focal35mm: Double = 0.0,
    /** The lens f-number (the container's apertures[0]); 0 = absent (the RawMeta convention). */
    val aperture: Double = 0.0,
) {
    /**
     * The binding key: "<camera>|<focal>mm" plus "|f/<aperture>" when
     * the aperture is carried, both rounded to 0.1 (the container's
     * float noise must not split one lens into two keys; real lenses
     * sit far apart). Meaningful on a VALIDATED profile — the store
     * matches only profiles that passed [LensProfile.validate].
     */
    fun bindingKey(): String {
        val sb = StringBuilder(camera)
        sb.append('|').append(round1(focal35mm)).append("mm")
        if (aperture > 0.0) {
            sb.append("|f/").append(round1(aperture))
        }
        return sb.toString()
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
}

/**
 * The intrinsics calibration — the warp's inputs, stored at the dims
 * they were calibrated against. The warp consumes the PIXEL focal
 * (StabilizationParams.focalLengthPx) and the principal point in
 * pixels; the mm focal is the optical identity the calibration also
 * knows (the brief's "focal length mm"). The container can derive
 * NEITHER (no sensor pitch exists in the MCRAW metadata) — that
 * absence is exactly why a profile must carry them.
 */
data class LensIntrinsics(
    /** The lens's TRUE optical focal in mm (not the 35mm-equivalent). */
    val focalLengthMm: Double = 0.0,
    /** The pixel focal AT [sensorWidthPx]/[sensorHeightPx] — the value the warp consumes. */
    val focalLengthPx: Double = 0.0,
    /** The sensor's pixel width the intrinsics were calibrated against. */
    val sensorWidthPx: Int = 0,
    /** The sensor's pixel height the intrinsics were calibrated against. */
    val sensorHeightPx: Int = 0,
    /**
     * The principal point x in pixels at the calibration dims;
     * 0 = the center sentinel (the core's exact convention — resolved
     * to the buffer center by the consumer, dim-independent).
     */
    val principalPointX: Double = 0.0,
    /** The principal point y in pixels; 0 = the center sentinel. */
    val principalPointY: Double = 0.0,
) {
    /**
     * The pixel focal at another buffer width — the plan computes at
     * the preview tier AND full resolution (Warp.h: the plan's dims
     * are the warped buffer's dims), so the focal scales
     * proportionally: f(bufW) = f_px · bufW / sensorWidthPx. Identity
     * at the calibration width; 0 for non-positive inputs (the core's
     * refusal convention — never a fabricated focal). A non-zero
     * principal point scales the same way (pp·bufW/sensorWidthPx);
     * the 0 sentinel needs no scaling (it resolves to whatever
     * buffer's center).
     */
    fun focalLengthPxAt(width: Int): Double =
        if (width <= 0 || sensorWidthPx <= 0) 0.0
        else focalLengthPx * width / sensorWidthPx
}

/**
 * The Brown-Conrady distortion set (see the package KDoc for the model
 * and the choice). Every term defaults 0 and renders omit-when-zero;
 * [isIdentity] — the all-zero set — IS the pinhole the warp assumes
 * today, so an uncalibrated profile is a no-op by construction.
 */
data class LensDistortion(
    /** Radial term, r² coefficient. */
    val k1: Double = 0.0,
    /** Radial term, r⁴ coefficient. */
    val k2: Double = 0.0,
    /** Radial term, r⁶ coefficient. */
    val k3: Double = 0.0,
    /** Tangential term, x. */
    val p1: Double = 0.0,
    /** Tangential term, y. */
    val p2: Double = 0.0,
) {
    /** True when no term is carried — the pinhole identity (the warp's current assumption). */
    fun isIdentity(): Boolean =
        k1 == 0.0 && k2 == 0.0 && k3 == 0.0 && p1 == 0.0 && p2 == 0.0
}

/**
 * One stabilization mode's crop cap — the profile's recommendation for
 * the grade fields, in their EXACT meaning: [mode] is
 * GradeK.stabilizationMode's int (0 = Smooth, 1 = Lock First Frame,
 * 2 = Horizon Lock), [zoomLimit] is GradeK.stabilizationZoomLimit's
 * scale (>= 1; the core clamps appliedZoom to min(required, this)).
 * A narrow-FOV lens (periscope) needs a bigger crop budget for the
 * same angular correction — the physically-motivated per-lens cap.
 */
data class LensStabMode(
    /** The grade's stabilizationMode int: 0 = Smooth, 1 = Lock First Frame, 2 = Horizon Lock. */
    val mode: Int,
    /** The crop cap in the grade's stabilizationZoomLimit scale; >= 1 (a warp may not magnify). */
    val zoomLimit: Double,
)

/**
 * One validation failure, in the app's stage/cause/recovery error
 * shape (DESIGN.md Error states — the PreviewErrors precedent: name
 * the stage, the cause, and the recovery, never a bare "invalid").
 */
data class LensProfileError(
    /** The schema block the failure belongs to: identity / intrinsics / distortion / modes / profile. */
    val stage: String,
    /** What is wrong, naming the value. */
    val cause: String,
    /** The user action that fixes it. */
    val recovery: String,
) {
    /** The one-line user-facing message, the PreviewErrors shape. */
    fun message(): String =
        "Lens profile rejected (stage: $stage): $cause — recovery: $recovery"
}
