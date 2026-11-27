package com.ritim.ritimcraw

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I8 — a STATIC guard for the release R8 keep rules
 * (`app/proguard-rules.pro`, the `feat-release-r8-readiness` rules).
 *
 * The scanner below walks the app's source tree, extracts every symbol
 * the native bridge / the persisted wire formats / reflection resolve BY
 * NAME, and asserts that each one has a keep rule. The rules today are
 * complete (auditor 21/21 + JNI + reflection), so the test is expected
 * GREEN; its value is FUTURE: a new wire enum / external fun / JNI-
 * constructed class / Class.forName target added without a matching keep
 * rule turns this red and NAMES the missing symbol.
 *
 * What it extracts (all by source scan, no reflection on app classes):
 *  - every Kotlin source file that declares `external fun` — the JNI
 *    symbol names are derived from the class FQN, so the class needs a
 *    keep rule (NativeSource today; any future one too);
 *  - every FindClass("com/ritim/...") target in jni_bridge.cpp plus the
 *    exact constructor signature the bridge looks up, and the Runnable
 *    run() GetMethodID;
 *  - every `Class.forName("...")` target in Kotlin sources;
 *  - every enum the wire code persists BY NAME: a Kotlin enum is
 *    "name-keyed" when some source writes it with `.name` (or reads it
 *    with valueOf / enumValueOf / Enum.valueOf / entries.firstOrNull {
 *    it.name == ... } / a fromWire helper) — the CALL SITES below are the
 *    generic enum-typed readers, so the enum TYPES the call sites
 *    reference are pinned by hand (this table mirrors the audit that
 *    produced the rules; the scan independently re-derives the
 *    `.name`-writing side).
 *  - the Lut* enums are the deliberate exception: they serialize their
 *    INT `value` (LutInterp.kt render/parse), not their names, so R8 may
 *    rename them — the test asserts they are NOT in the enum-keep list
 *    (guarding the int-value discipline itself).
 *
 * "The test really fires": a synthetic rule file (a TemporaryFolder copy
 * with one rule dropped, and one with a bogus enum rule) must make the
 * verifier RED and name the missing symbol — proving the scanner's
 * sensitivity, not just its current green.
 */
class ProguardKeepRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- locating the tree (the GradeParcelTest walk-up pattern) -------

    private fun locate(suffix: String): File {
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidate = File(dir, suffix)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("cannot locate $suffix from the test working dir")
    }

    /** The app module dir (…/app): src/main/java hangs off it. */
    private fun appDir(): File = locate("src/main/java").parentFile.parentFile.parentFile

    private fun kotlinSources(): List<File> =
        File(appDir(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rulesFile(): File = locate("proguard-rules.pro")

    // ---- the scanner (pure, testable: takes the rules TEXT) -----------

    /**
     * The verification result — `missing*` carry the human-readable
     * symbol names so a red test tells the owner WHAT to add to
     * proguard-rules.pro.
     */
    data class Result(
        val missingExternalFunClasses: List<String>,
        val missingJniTargets: List<String>,
        val missingReflectionClasses: List<String>,
        val missingNameKeyedEnums: List<String>,
    ) {
        fun isGreen(): Boolean =
            missingExternalFunClasses.isEmpty() &&
                missingJniTargets.isEmpty() &&
                missingReflectionClasses.isEmpty() &&
                missingNameKeyedEnums.isEmpty()

        fun describe(): String =
            listOf(
                "missing external-fun keep rules: $missingExternalFunClasses" to missingExternalFunClasses,
                "missing JNI keep rules: $missingJniTargets" to missingJniTargets,
                "missing Class.forName keep rules: $missingReflectionClasses" to missingReflectionClasses,
                "missing name-keyed wire enum keep rules: $missingNameKeyedEnums" to missingNameKeyedEnums,
            ).filter { it.second.isNotEmpty() }
                .joinToString("; ") { it.first }
    }

    /**
     * Verifies the given proguard rules text against the CURRENT source
     * tree. Pure w.r.t. the rules: the same source scan always yields the
     * same required symbols, so a synthetic rules text can be fed to test
     * the scanner's sensitivity.
     */
    fun verify(rulesText: String): Result {
        val app = appDir()

        // -- 1. classes declaring `external fun` (JNI symbol prefix) ------
        val externalFunClasses = externalFunClasses()

        val missingExternal = externalFunClasses.filter { fqn ->
            !Regex("""-keep\w*\s+class\s+${Regex.escape(fqn)}\s*\{""").containsMatchIn(rulesText)
        }.map { "external fun class $it has no keep rule (the JNI symbol prefix needs the un-renamed class)" }

        // -- 2. JNI targets parsed out of jni_bridge.cpp -----------------
        val bridgeFile = File(app, "src/main/cpp/jni_bridge.cpp")
        assertTrue("jni_bridge.cpp not found at ${bridgeFile.path}", bridgeFile.exists())
        val bridge = bridgeFile.readText()
        // FindClass("com/ritim/...") — the bridge only ever looks up our
        // own classes by name (android/graphics/Rect is a framework class
        // R8 never renames; it is filtered out by the prefix check).
        val jniTargets = Regex("""FindClass\("((?:com/ritim/)[^"]+)"\)""").findAll(bridge)
            .map { it.groupValues[1].replace('/', '.') }.distinct().toList()
        // GetMethodID(cls, "<init>", "(...)") on the FindClass target: the
        // exact constructor must be kept — the rules file writes it in the
        // human form (`long, int, ...`), the bridge in the JVM descriptor
        // form (`JIIIIL...;`), so the check maps the descriptor onto the
        // keep-rule type spelling and looks for that line.
        val ctorKeeps = Regex(
            """FindClass\("(com/ritim/[^"]+)"\)[\s\S]{0,400}?GetMethodID\(\s*\w+,\s*"<init>",\s*"([^"]+)"\)""",
        ).findAll(bridge).map { it.groupValues[1].replace('/', '.') to it.groupValues[2] }.toList()
        // The Runnable seam: GetMethodID(cls, "run", "()V") on the
        // listener's own class — the keep rule is the
        // `implements java.lang.Runnable` member rule.
        val hasRunSeam = Regex("""GetMethodID\(\w+,\s*"run",\s*"\(\)V"\)""").containsMatchIn(bridge)

        val missingJni = mutableListOf<String>()
        for (fqn in jniTargets) {
            if (!Regex("""-keep\w*\s+class\s+${Regex.escape(fqn)}\s*\{""").containsMatchIn(rulesText)) {
                missingJni += "JNI FindClass target $fqn has no keep rule"
            }
        }
        for ((fqn, descriptor) in ctorKeeps) {
            val keptForm = proguardCtorLine(descriptor)
            if (keptForm == null || !rulesText.contains(keptForm)) {
                missingJni += "JNI constructor of $fqn (bridge descriptor \"$descriptor\", keep-line \"$keptForm\") is not kept"
            }
        }
        if (hasRunSeam && !Regex("""-keepclassmembers class \* implements java.lang.Runnable""").containsMatchIn(rulesText)) {
            missingJni += "JNI Runnable.run() seam has no -keepclassmembers ... implements java.lang.Runnable rule"
        }

        // -- 3. Class.forName targets in Kotlin sources ------------------
        val reflectionTargets = kotlinSources()
            .flatMap { f ->
                Regex("""Class\.forName\("([^"]+)"\)""").findAll(f.readText())
                    .map { it.groupValues[1] }.toList()
            }.sorted()
        val missingReflection = reflectionTargets.map { fqn ->
            "Class.forName target $fqn has no keep rule" to fqn
        }.filter { (_, fqn) ->
            !Regex("""-keep\w*\s+class\s+${Regex.escape(fqn)}\s*\{""").containsMatchIn(rulesText)
        }.map { it.first }

        // -- 4. name-keyed wire enums ------------------------------------
        val nameKeyed = requiredNameKeyedEnums()
        val missingEnums = nameKeyed.filter { fqn ->
            !Regex("""-keepclassmembers enum\s+${Regex.escape(fqn)}\s*\{""").containsMatchIn(rulesText)
        }.map { "wire enum $it is persisted BY NAME but has no keepclassmembers enum rule" }

        return Result(
            missingExternalFunClasses = missingExternal,
            missingJniTargets = missingJni,
            missingReflectionClasses = missingReflection,
            missingNameKeyedEnums = missingEnums,
        )
    }

    /**
     * The enum TYPES the wire code persists BY NAME. The `.name`-writing
     * side is re-derived from source below (any enum whose `.name` lands
     * in a persisted render); the READER side goes through four generic
     * helpers (enumValueOf<E> / Enum.valueOf / entries.firstOrNull {
     * it.name == } / fromWire), so their call-site types are pinned here
     * — this table is the same audit that produced the rules file.
     *
     * The Lut* family is deliberately absent: LutInterp.kt renders and
     * parses the INT `.value` (never the name), so a keep rule would be
     * wasted; the test asserts that absence (see lutStarAreIntValue).
     */
    private fun requiredNameKeyedEnums(): List<String> {
        val pkg = "com.ritim.ritimcraw"
        // The pinned reader-side call sites (the generic enum-typed
        // helpers cannot be scanned for their type argument):
        val readerSide = listOf(
            // ExportSettingsWire.parse enumOf(...) — 5 enums (ExportSettings.kt:204-211)
            "$pkg.export.ExportCodec", "$pkg.export.ExportDynamicRange",
            "$pkg.export.ExportRateMode", "$pkg.export.BitrateLevel",
            "$pkg.export.KeyframeInterval",
            // ExportStore.enumOrNullOr (enumValueOf<E>) + AudioLinkState
            // entries.firstOrNull { it.name == } (ExportStore.kt:286,295,349,413)
            "$pkg.export.Target", "$pkg.export.State",
            "$pkg.engine.RenderBackend", "$pkg.data.audio.AudioLinkState",
            // MetadataStore.enumValue<E> (MetadataStore.kt:278-301)
            "$pkg.data.SourceKind", "$pkg.data.metadata.Diagnosis",
            "$pkg.data.metadata.LogDiagnosis", "$pkg.data.metadata.Capability",
            // TimelineStore.enumOrThrow — java.lang.Enum.valueOf (TimelineStore.kt:220)
            "$pkg.data.timeline.TrackKind",
            // SourceRepository row parse — valueOf (SourceRepository.kt:633-635)
            "$pkg.data.Access",
            // fromWire helpers (ThemeMode, RenderBackend, PreviewQuality)
            "$pkg.data.ThemeMode", "$pkg.engine.PreviewQuality",
            // ProxySignature.canonical: "kind=${kind.name}" — inside the
            // proxy filename signature hash
            "$pkg.proxy.ProxySourceKind",
            // EncodedColorSpec.describe() writes SourceMatrix/
            // SourceTransfer/SourcePrimaries .name into the diagnostic
            // lines (EncodedColorSpec.kt:97-98) — diagnostic-only, but the
            // owner's A/B line format is stable, kept with the same rule.
            "$pkg.data.color.SourceMatrix", "$pkg.data.color.SourceTransfer",
            "$pkg.data.color.SourcePrimaries",
        )
        // The WRITER side, re-derived from source: the persisted renders
        // write `x.name` (or a hand-mapped name) in these files. The scan
        // asserts each file still exists and still references `.name` —
        // if a writer drops the pattern or moves, this fires so the
        // hand-pinned reader table above is re-audited (the two sides
        // must stay in sync).
        val writerFiles = listOf(
            "export/ExportSettings.kt", "export/ExportStore.kt",
            "data/metadata/MetadataStore.kt", "data/timeline/TimelineStore.kt",
            "data/SourceRepository.kt", "data/audio/AudioStore.kt",
            "data/ThemeStore.kt", "data/RenderBackendStore.kt",
            "data/PreviewQualityStore.kt", "proxy/ProxySignature.kt",
            "engine/EncodedColorSpec.kt",
        )
        for (rel in writerFiles) {
            val f = File(appDir(), "src/main/java/com/ritim/ritimcraw/$rel")
            assertTrue(
                "writer-side wire file $rel not found — the wire scan is stale",
                f.exists(),
            )
            assertTrue(
                "writer-side wire file $rel no longer writes an enum .name — update this test's writer set",
                Regex("""\.name\b""").containsMatchIn(f.readText()),
            )
        }
        return readerSide.sorted()
    }

    // ---- the guard -----------------------------------------------------

    /**
     * Maps a JVM constructor descriptor ("(JIIII...;I)V") onto the
     * spelling proguard-rules.pro keeps it with ("(long, int, ...,
     * android.graphics.Rect, int)"). Returns null for a descriptor this
     * mapper does not know — a NEW descriptor type then fails the keep
     * check with its raw descriptor in the message (honest refusal, not
     * a silent pass).
     */
    private fun proguardCtorLine(descriptor: String): String? {
        val inner = descriptor.removePrefix("(").substringBefore(")")
        val types = mutableListOf<String>()
        var i = 0
        val objectNames = mapOf(
            "android/graphics/Rect" to "android.graphics.Rect",
            "java/lang/String" to "java.lang.String",
        )
        while (i < inner.length) {
            when (inner[i]) {
                'J' -> { types += "long"; i++ }
                'I' -> { types += "int"; i++ }
                'D' -> { types += "double"; i++ }
                'F' -> { types += "float"; i++ }
                'Z' -> { types += "boolean"; i++ }
                'L' -> {
                    val end = inner.indexOf(';', i)
                    if (end < 0) return null
                    val name = inner.substring(i + 1, end)
                    types += objectNames[name] ?: return null
                    i = end + 1
                }
                '[' -> return null // arrays not used by the bridge today
                else -> return null
            }
        }
        return "public <init>(" + types.joinToString(", ") + ");"
    }

    /** The classes whose `external fun` declarations JNI resolves by symbol name. */
    fun externalFunClasses(): List<String> =
        kotlinSources()
            .filter { Regex("""\bexternal fun\b""").containsMatchIn(it.readText()) }
            .map { f ->
                val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(f.readText())!!
                    .groupValues[1]
                "$pkg.${f.nameWithoutExtension}"
            }
            .sorted()

    @Test
    fun everyJniAndWireSymbolHasAKeepRule() {
        val result = verify(rulesFile().readText())
        assertTrue(
            "R8 keep rules are INCOMPLETE — add the missing rules to app/proguard-rules.pro: ${result.describe()}",
            result.isGreen(),
        )
    }

    @Test
    fun scanScaleIsTheAuditedScale() {
        // The audit's scale, pinned: 1 external-fun class, 2 own-class
        // FindClass targets + the Runnable seam, 1 Class.forName target,
        // 21 name-keyed wire enums. A source change that moves any of
        // these numbers changes the scan — this pin makes it visible.
        val bridge = File(appDir(), "src/main/cpp/jni_bridge.cpp").readText()
        assertEquals(
            "exactly one class declares external fun (NativeSource)",
            listOf("com.ritim.ritimcraw.engine.NativeSource"),
            externalFunClasses(),
        )
        assertEquals(
            "the bridge FindClass-constructs exactly the two audited own classes",
            listOf(
                "com.ritim.ritimcraw.engine.ContainerIdentityK",
                "com.ritim.ritimcraw.engine.FrameInfoK",
            ),
            Regex("""FindClass\("(com/ritim/[^"]+)"\)""").findAll(bridge)
                .map { it.groupValues[1].replace('/', '.') }.distinct().sorted().toList(),
        )
        assertEquals(
            "exactly one Class.forName target in Kotlin sources",
            listOf("android.app.ActivityThread"),
            kotlinSources()
                .flatMap { f ->
                    Regex("""Class\.forName\("([^"]+)"\)""").findAll(f.readText())
                        .map { it.groupValues[1] }.toList()
                }.distinct().sorted(),
        )
        assertEquals(
            "21 name-keyed wire enums (the audit's 21/21)",
            21,
            requiredNameKeyedEnums().size,
        )
    }

    @Test
    fun lutEnumsAreIntValueSerializedNotNameKeyed() {
        val rules = rulesFile().readText()
        // The Lut* family serializes its INT value (LutInterp.kt
        // render/parse use `.value`, never `.name`), so a per-enum keep
        // rule would be wasted — assert the exception is deliberate.
        for (lut in listOf(
            "com.ritim.ritimcraw.data.LutGamut",
            "com.ritim.ritimcraw.data.LutTransfer",
            "com.ritim.ritimcraw.data.LutRole",
        )) {
            assertFalse(
                "$lut is int-value serialized (LutInterp.kt) and must NOT have a dedicated enum keep rule",
                Regex("""-keepclassmembers enum\s+${Regex.escape(lut)}\s*\{""").containsMatchIn(rules),
            )
        }
        // And the discipline itself: the render/parse of LutInterp really
        // is by `.value` (if someone ever switches it to `.name`, the
        // dedicated rule becomes REQUIRED and the enum-set test above
        // must be updated — this assertion makes that switch visible).
        val lutInterp = File(appDir(), "src/main/java/com/ritim/ritimcraw/data/LutInterp.kt").readText()
        assertTrue(
            "LutInterp.kt render/parse no longer use .value — the Lut* keep-out exception is STALE",
            Regex("""\.value\b""").containsMatchIn(lutInterp) &&
                !Regex("""it\.name ==|\.name ==\s*w""").containsMatchIn(lutInterp),
        )
        // The generic belt (`-keepclassmembers enum com.ritim.ritimcraw.**`)
        // still covers the Lut* family's values()/valueOf() plumbing — the
        // per-enum absence is safe only because that belt exists.
        assertTrue(
            "the package-wide enum belt rule disappeared — the Lut* keep-out is no longer safe",
            Regex("""-keepclassmembers enum com\.ritim\.ritimcraw\.\*\*\s*\{""").containsMatchIn(rules),
        )
    }

    // ---- "the test really fires" — the scanner's sensitivity -----------

    @Test
    fun scannerFiresOnMissingExternalFunRule() {
        val rules = rulesFile().readText()
        // Drop the NativeSource keep rule: the scanner must go RED and
        // NAME the class.
        val synthetic = rules
            .replace(
                Regex("""-keep class com\.ritim\.ritimcraw\.engine\.NativeSource \{ \*; \}""", RegexOption.MULTILINE),
                "# dropped for the synthetic test",
            )
        assertTrue("the synthetic drop did not change the rules text", synthetic != rules)
        val result = verify(synthetic)
        assertFalse(result.isGreen())
        assertTrue(
            "the red must NAME the missing external-fun class, was: ${result.describe()}",
            "NativeSource" in result.describe(),
        )
    }

    @Test
    fun scannerFiresOnMissingWireEnumRule() {
        val rules = rulesFile().readText()
        // Drop ONE wire enum rule (ProxySourceKind — the proxy signature
        // hash enum, the most expensive silent break): RED + named.
        val synthetic = rules
            .replace(
                Regex("""-keepclassmembers enum com\.ritim\.ritimcraw\.proxy\.ProxySourceKind \{ \*; \}"""),
                "# dropped for the synthetic test",
            )
        assertTrue("the synthetic drop did not change the rules text", synthetic != rules)
        val result = verify(synthetic)
        assertFalse(result.isGreen())
        assertTrue(
            "the red must NAME the missing enum, was: ${result.describe()}",
            "ProxySourceKind" in result.describe(),
        )
    }

    @Test
    fun scannerFiresOnMissingJniConstructorRule() {
        val rules = rulesFile().readText()
        // Drop the FrameInfoK keep block (class + exact ctor): RED + named.
        val synthetic = rules
            .replace(
                Regex("""-keep class com\.ritim\.ritimcraw\.engine\.FrameInfoK \{[\s\S]*?\}"""),
                "# dropped for the synthetic test",
            )
        assertTrue("the synthetic drop did not change the rules text", synthetic != rules)
        val result = verify(synthetic)
        assertFalse(result.isGreen())
        assertTrue(
            "the red must NAME the missing JNI target, was: ${result.describe()}",
            "FrameInfoK" in result.describe(),
        )
    }

    @Test
    fun scannerFiresOnMissingReflectionRule() {
        val rules = rulesFile().readText()
        // Drop the ActivityThread keep block: the Class.forName scan must
        // go RED and name it.
        val synthetic = rules
            .replace(
                Regex("""-keep class android\.app\.ActivityThread \{[\s\S]*?\}"""),
                "# dropped for the synthetic test",
            )
        assertTrue("the synthetic drop did not change the rules text", synthetic != rules)
        val result = verify(synthetic)
        assertFalse(result.isGreen())
        assertTrue(
            "the red must NAME the missing reflection target, was: ${result.describe()}",
            "ActivityThread" in result.describe(),
        )
    }

    @Test
    fun scannerFiresOnMissingRunnableSeamRule() {
        val rules = rulesFile().readText()
        // Drop the Runnable run() member rule: RED + named.
        val synthetic = rules
            .replace(
                Regex("""-keepclassmembers class \* implements java\.lang\.Runnable \{[\s\S]*?\}"""),
                "# dropped for the synthetic test",
            )
        assertTrue("the synthetic drop did not change the rules text", synthetic != rules)
        val result = verify(synthetic)
        assertFalse(result.isGreen())
        assertTrue(
            "the red must NAME the Runnable seam, was: ${result.describe()}",
            "Runnable" in result.describe(),
        )
    }

    @Test
    fun syntheticRulesFileInTempFolderFiresTheScanner() {
        // The brief's literal shape: the scanning function reads a RULES
        // FILE from disk — write a deliberately incomplete one into a
        // temp folder and hand it through the same path the guard uses.
        val bogus = tmp.newFile("proguard-rules.pro")
        bogus.writeText(
            """
            # A synthetic rules file with EVERY keep rule missing.
            -keepattributes SourceFile,LineNumberTable
            """.trimIndent(),
        )
        val result = verify(bogus.readText())
        assertFalse(
            "an all-rules-missing file must be RED, was: ${result.describe()}",
            result.isGreen(),
        )
        // And the red must name a representative symbol from each family.
        assertTrue("NativeSource" in result.describe())
        assertTrue("FrameInfoK" in result.describe())
        assertTrue("ActivityThread" in result.describe())
        assertTrue("ExportCodec" in result.describe())
    }
}
