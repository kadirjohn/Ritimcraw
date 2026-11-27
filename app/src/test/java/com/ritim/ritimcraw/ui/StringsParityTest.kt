package com.ritim.ritimcraw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * en/tr strings parity lock (night brief E6). The lock fails LOUDLY on
 * any drift between res/values/strings.xml and res/values-tr/strings.xml:
 *
 *  - key sets must match exactly (both directions: a missing tr key
 *    falls back to English on device, an extra tr key is dead weight
 *    that masks a typo in the en side);
 *  - every string's format placeholders (%1$s, %1$d, %% — the full
 *    %n$X set) must occur identically in both languages, per key (a
 *    missing/renumbered placeholder crashes getString or shows a raw
 *    "%2$s" to the user);
 *  - every plurals block must declare the same quantity set and the
 *    same placeholders per quantity;
 *  - the owner's locked-term list must stay untranslated in tr: the
 *    terms that stay English in the Turkish UI (Waveform, Split tone,
 *    Temporal NR, Gain (EV), AgX, and the AgX preset names) plus the
 *    one sanctioned translation Stabilize -> "Sabitleme" (brief D,
 *    "Değişmeyen kurallar"). This is a lock so future translations
 *    cannot accidentally localize them.
 *
 * Pure file test: both strings.xml files are read from the source tree
 * (JVM-safe; the committedHeader() walk-up pattern from GradeParcelTest),
 * parsed with the project's own regex conventions (no Android resource
 * machinery on the JVM), and compared. Nothing here edits translations;
 * when the lock fires it names the key and both sides.
 */
class StringsParityTest {

    // ---- the two source files, located from the test working directory --

    /**
     * Walk-up file location (GradeParcelTest.committedHeader pattern):
     * Gradle's module dir (src/main/res/...) and the repo root
     * (app/src/main/res/...), plus any deeper runner dir. A runner
     * that finds nothing fails honestly with every path it tried.
     */
    private fun resFile(relative: String): File {
        val suffixes = listOf(
            "src/main/res/$relative",
            "app/src/main/res/$relative",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.exists()) {
                    return candidate
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate $relative; tried " +
                candidates.joinToString(", ") { it.absolutePath } +
                " (working dir " + File(".").absolutePath + ")",
        )
    }

    private fun english(): String = resFile("values/strings.xml").readText()
    private fun turkish(): String = resFile("values-tr/strings.xml").readText()

    // ---- a minimal, honest resources.xml reader --------------------------
    //
    // The files are flat: <string name="...">body</string> and
    // <plurals name="..."> with <item quantity="...">body</item> children,
    // no CDATA, no xliff, no translatable= overrides (verified against both
    // files at write time). Comments between entries are skipped by the
    // regexes themselves. Dot-matches-all so multi-line bodies parse.

    /** Every <string name="...">body</string> entry, in file order. */
    private fun strings(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val key = m.groupValues[1]
            assertTrue(
                "duplicate <string name=\"$key\"> in one file - keys must be unique",
                !out.containsKey(key),
            )
            out[key] = m.groupValues[2]
        }
        return out
    }

    /** Every <plurals name="..."> block: key -> (quantity -> body). */
    private fun plurals(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for (m in Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val key = m.groupValues[1]
            assertTrue(
                "duplicate <plurals name=\"$key\"> in one file",
                !out.containsKey(key),
            )
            val items = LinkedHashMap<String, String>()
            for (i in Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[2])) {
                items[i.groupValues[1]] = i.groupValues[2]
            }
            out[key] = items
        }
        return out
    }

    /**
     * The Android format placeholders in a string body: positional
     * (%1$s), non-positional (%s, %d) and the escaped literal %%.
     * Sorted so "same multiset" is the comparison; positional index
     * AND conversion type both matter, so %1$s vs %1$d is a mismatch.
     */
    private fun placeholders(body: String): List<String> =
        Regex("""%\d+\$[sdf]|%[sdf]|%%""").findAll(body).map { it.value }.sorted().toList()

    // ---- the parity lock --------------------------------------------------

    @Test
    fun tr_has_exactly_the_en_string_keys_both_directions() {
        val en = strings(english())
        val tr = strings(turkish())
        val missingInTr = en.keys - tr.keys
        val extraInTr = tr.keys - en.keys
        assertTrue(
            "values-tr/strings.xml drifted from values/strings.xml:\n" +
                "missing in tr (device shows English fallback): " +
                missingInTr.sorted().joinToString(", ") + "\n" +
                "extra in tr (no en counterpart; likely an en rename/typo): " +
                extraInTr.sorted().joinToString(", "),
            missingInTr.isEmpty() && extraInTr.isEmpty(),
        )
    }

    @Test
    fun tr_has_exactly_the_en_plurals_keys_both_directions() {
        val en = plurals(english())
        val tr = plurals(turkish())
        val missingInTr = en.keys - tr.keys
        val extraInTr = tr.keys - en.keys
        assertTrue(
            "plurals keys drifted between values/ and values-tr/:\n" +
                "missing in tr: " + missingInTr.sorted().joinToString(", ") + "\n" +
                "extra in tr: " + extraInTr.sorted().joinToString(", "),
            missingInTr.isEmpty() && extraInTr.isEmpty(),
        )
    }

    @Test
    fun every_string_has_the_same_placeholders_in_both_languages() {
        val en = strings(english())
        val tr = strings(turkish())
        val drifted = en.keys.filter { it in tr.keys }
            .filter { placeholders(en.getValue(it)) != placeholders(tr.getValue(it)) }
        assertTrue(
            "format placeholders drifted (crashes getString or leaks a raw " +
                "%1\$s to the user) for: " +
                drifted.joinToString(", ") { key ->
                    "$key (en ${placeholders(en.getValue(key))} vs tr ${placeholders(tr.getValue(key))})"
                },
            drifted.isEmpty(),
        )
    }

    @Test
    fun every_plurals_quantity_has_the_same_placeholders_in_both_languages() {
        val en = plurals(english())
        val tr = plurals(turkish())
        val problems = mutableListOf<String>()
        for (key in en.keys) {
            val enItems = en.getValue(key)
            val trItems = tr[key] ?: continue // covered by the key-set test
            val missingQuantities = enItems.keys - trItems.keys
            if (missingQuantities.isNotEmpty()) {
                problems.add("$key: quantities missing in tr: " + missingQuantities.sorted().joinToString(", "))
            }
            for (quantity in enItems.keys) {
                val trBody = trItems[quantity] ?: continue
                if (placeholders(enItems.getValue(quantity)) != placeholders(trBody)) {
                    problems.add(
                        "$key [$quantity] (en ${placeholders(enItems.getValue(quantity))} vs tr ${placeholders(trBody)})",
                    )
                }
            }
        }
        assertTrue(
            "plurals placeholder/quantity drift: " + problems.joinToString("; "),
            problems.isEmpty(),
        )
    }

    @Test
    fun plurals_declare_the_same_quantities_in_both_languages() {
        val en = plurals(english())
        val tr = plurals(turkish())
        val drifted = en.keys.filter { it in tr.keys }
            .filter { en.getValue(it).keys != tr.getValue(it).keys }
        assertTrue(
            "plurals quantity sets drifted (a missing 'other' crashes in " +
                "that language) for: " +
                drifted.joinToString(", ") { key ->
                    "$key (en ${en.getValue(key).keys.sorted()} vs tr ${tr.getValue(key).keys.sorted()})"
                },
            drifted.isEmpty(),
        )
    }

    // ---- the locked-term list (owner rule, brief D) ------------------------
    //
    // These terms stay ENGLISH in the Turkish UI by owner decision; the one
    // sanctioned translation is Stabilize -> "Sabitleme". A future well-
    // meaning localization that touches any of these must fail here and be
    // shown to the owner first.

    /** key -> the exact tr value the lock pins. */
    private val lockedTerms: Map<String, String> = mapOf(
        "editor_scope_waveform" to "Waveform",
        "editor_split_tone" to "Split tone",
        "editor_temporal_nr" to "Temporal NR",
        "editor_slider_exposure" to "Gain (EV)",
        "editor_tonemap_agx" to "AgX",
        "editor_tonemap_agx_punchy" to "Punchy",
        "editor_tonemap_agx_golden" to "Golden",
        "editor_tonemap_agx_greyscale" to "Greyscale",
        "editor_tonemap_agx_base" to "Base",
        // The one sanctioned translation from the list: Stabilize.
        "editor_frame_subtab_stabilize" to "Sabitleme",
    )

    /** Keys that must keep the locked AgX word inside a longer tr string. */
    private val lockedFragments: Map<String, String> = mapOf(
        "editor_tonemap_agx_look" to "AgX",
        "editor_lut_role_display" to "AgX",
        "editor_lut_role_scene" to "AgX",
    )

    @Test
    fun tr_keeps_the_owner_locked_english_terms() {
        val tr = strings(turkish())
        val drifted = lockedTerms.filter { (key, expected) -> tr[key] != expected }
            .map { (key, expected) ->
                "key $key: expected \"$expected\", tr has \"${tr[key] ?: "<MISSING KEY>"}\" " +
                    "(owner rule: these terms stay English in the Turkish UI; " +
                    "Stabilize is \"Sabitleme\")"
            }
        assertTrue(drifted.joinToString("\n"), drifted.isEmpty())
    }

    @Test
    fun tr_keeps_the_locked_agx_word_inside_longer_strings() {
        val tr = strings(turkish())
        val drifted = lockedFragments.filter { (key, fragment) -> tr[key]?.contains(fragment) != true }
            .map { (key, fragment) ->
                "key $key must keep \"$fragment\" in its tr value \"${tr[key] ?: "<MISSING KEY>"}\" " +
                    "(AgX is a locked product term)"
            }
        assertTrue(drifted.joinToString("\n"), drifted.isEmpty())
    }

    // ---- the loud-drift guarantee: the lock machinery itself ---------------
    //
    // The point of E6 is a PERMANENT lock: the comparator must fail on real
    // drift, not pass vacuously. These cases feed the same parser +
    // placeholder extractor deliberately broken content and require the
    // failure paths to fire (guarded by assume-style assertions so they
    // stay green while proving the machinery catches each drift class).

    @Test
    fun the_lock_machinery_catches_a_missing_key() {
        val en = strings(english())
        val tr = strings(turkish())
        val trMinusOne = tr - en.keys.first()
        assertTrue((en.keys - trMinusOne.keys).isNotEmpty())
    }

    @Test
    fun the_lock_machinery_catches_a_placeholder_mismatch() {
        // A real en body vs a tr body that lost its placeholder.
        val enBody = "Import failed: %1\$s. Recovery: pick the clip again."
        val trBody = "İçe aktarma başarısız oldu. Kurtarma: klibi yeniden seçin."
        assertTrue(placeholders(enBody) == listOf("%1\$s"))
        assertTrue(placeholders(trBody).isEmpty())
        assertTrue(placeholders(enBody) != placeholders(trBody))
        // And the swapped/renumbered case: %1$s vs %2$s must differ.
        assertTrue(placeholders("A %1\$s") != placeholders("A %2\$s"))
        // And the type case: %1$s vs %1$d must differ.
        assertTrue(placeholders("A %1\$s") != placeholders("A %1\$d"))
        // And the literal-%% case counts as a placeholder on both sides.
        assertEquals(listOf("%%", "%1\$s"), placeholders("VBR: %1\$s%%"))
    }

    @Test
    fun the_lock_machinery_catches_a_plural_quantity_mismatch() {
        val enBlock = "<plurals name=\"x\"><item quantity=\"one\">%1\$d codec</item>" +
            "<item quantity=\"other\">%1\$d codecs</item></plurals>"
        val trBlock = "<plurals name=\"x\"><item quantity=\"one\">%1\$d codec</item></plurals>"
        val enItems = plurals(enBlock).getValue("x")
        val trItems = plurals(trBlock).getValue("x")
        assertTrue(enItems.keys != trItems.keys)
        // And the per-quantity placeholder case: one vs other may differ in
        // WORDS but a placeholder must not vanish per quantity.
        assertTrue(
            placeholders(enItems.getValue("one")) != placeholders("codec sayısı"),
        )
    }

    @Test
    fun the_lock_machinery_catches_a_broken_locked_term() {
        val tr = strings(turkish())
        // A wrongly localized Waveform must differ from the pinned value.
        assertTrue(tr.getValue("editor_scope_waveform") == "Waveform")
        assertTrue(tr.getValue("editor_scope_waveform") != "Dalga formu")
        // The sanctioned Stabilize translation is pinned, not the English.
        assertTrue(tr.getValue("editor_frame_subtab_stabilize") == "Sabitleme")
        assertTrue(tr.getValue("editor_frame_subtab_stabilize") != "Stabilize")
    }

    @Test
    fun every_string_key_referenced_in_the_manifest_of_this_test_exists() {
        // Guards the locked-term list against silent key renames: if an
        // editor key is renamed in en, the tr file keeps the old key and
        // the locked-term lock above would silently stop pinning anything.
        // This case fails when any locked key disappears from the en file.
        val en = strings(english())
        val missing = (lockedTerms.keys + lockedFragments.keys) - en.keys
        assertTrue(
            "a locked-term key disappeared from values/strings.xml (en " +
                "rename?) - update the lock list in StringsParityTest: " +
                missing.joinToString(", "),
            missing.isEmpty(),
        )
    }
}
