package com.ritim.ritimcraw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G6 static accessibility audit (night brief 2026-11-27): source-scanning
 * JVM tests over the ui package (app/src/main/java/com/ritim/ritimcraw/ui).
 *
 * NOTE: Kotlin block comments NEST — never write a bare slash-star-star
 * sequence inside a KDoc (it opens a nested comment); that is why this
 * comment says "slash-star-star" in words.
 *
 * These are REGEX-LEVEL scans, not a semantics tree walk: they catch the
 * regressions the categories below describe and deliberately pass the
 * deliberate patterns this codebase uses. Each scan pairs with an
 * EDITABLE EXCEPTION LIST (baseline/allowlist): a finding on the list is
 * reported-but-passing (with its reason carried in the list), and a NEW
 * violation — one the list does not name — fails the test. That makes
 * the suite a regression guard: as night-branch UI work lands, any new
 * unlabelled clickable / new hardcoded UI literal / new undersized fixed
 * target goes red, while the known-and-reasoned cases stay green.
 *
 * Source location follows GradeParcelTest's walk-up pattern (the test
 * working dir is the Gradle module dir in this repo, but the walk covers
 * any runner starting deeper or from the repo root).
 *
 * What the scans deliberately do NOT flag (false-positive classes, each
 * reasoned in its test):
 *  - Decorative `contentDescription = null` INSIDE a labelled clickable
 *    container (this codebase's pattern: the label rides the clickable's
 *    own `Modifier.semantics`, the glyph stays decorative — ZebraToggleButton,
 *    ScopeToggleButton, EditorTopBar's preset delete, the
 *    HighlightRecovery IconToggleButton). In Compose, `contentDescription
 *    = null` on a decorative glyph is CORRECT.
 *  - Small fixed ICON sizes (16–30dp glyphs): Material3 inflates clickable
 *    components through LocalMinimumInteractiveComponentSize to the 48dp
 *    minimum touch target, so a small glyph does not shrink the target.
 *    The scan only sizes the CLICKABLE node itself.
 */
class StaticA11yAuditTest {

    // ---- source location ------------------------------------------------

    /**
     * The ui/ source root, located from the test working directory
     * (GradeParcelTest's walk-up pattern: module dir and repo root both
     * covered, honest failure with the tried paths).
     */
    private fun uiRoot(): File {
        val suffixes = listOf(
            "src/main/java/com/ritim/ritimcraw/ui",
            "app/src/main/java/com/ritim/ritimcraw/ui",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the ui source root; tried " +
                candidates.joinToString(", ") { it.absolutePath } +
                " (working dir " + File(".").absolutePath + ")",
        )
    }

    /** All .kt files under the ui root, sorted for stable output. */
    private fun uiSources(): List<File> =
        uiRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    /** (file, 1-based line number, line text) for every line matching [pattern]. */
    private fun scan(pattern: Regex): List<Triple<String, Int, String>> {
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) {
                    hits.add(Triple(file.name, index + 1, line.trim()))
                }
            }
        }
        return hits
    }

    /** The report key for a finding: `FileName.kt:line` (branch-stable). */
    private fun key(file: String, line: Int): String = "$file:$line"

    // ---- scan 1: clickable + `contentDescription = null` on the same node --

    /**
     * The clickable-lacking-a-label scan. A CLICKABLE whose own modifier
     * chain carries `contentDescription = null` is the true violation
     * (the node itself is an action AND hides from screen readers).
     *
     * SAME-STATEMENT test, regex level: the null-description line and a
     * click wiring are on ONE modifier chain when they share a single
     * statement — the shapes this codebase writes:
     * `Modifier.clickable(...) { }.semantics { contentDescription = null }`
     * and the reverse order, all within one bracket-balanced statement.
     * The scan walks each `contentDescription = null` occurrence OUTWARD
     * to its enclosing statement (the nearest enclosing balanced-brace/
     * -paren region that starts at a `Modifier.` or `modifier =` chain)
     * and asks whether THAT statement also wires a click.
     *
     * The codebase's correct patterns pass by construction: a decorative
     * glyph's `contentDescription = null` sits inside an `Icon(...)`
     * CALL ARGUMENT (its own statement: `Icon(` ... `)`), never inside
     * the clickable node's own modifier chain; and a labelled clickable
     * carries its contentDescription in the SAME semantics block as the
     * click, but non-null.
     */
    private fun clickableWithHiddenDescription(): List<Triple<String, Int, String>> {
        val nullDesc = Regex("""contentDescription\s*=\s*null""")
        val clickable = Regex(
            """\bclickable\b|\bcombinedClickable\b|\btoggleable\b|\bselectable\b"""
        )
        val modifierChainStart = Regex("""\bmodifier\s*=|Modifier\s*\.""")
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!nullDesc.containsMatchIn(line)) return@forEachIndexed
                // Walk backward to the statement this null belongs to:
                // stop at the first line that opens a call/lambda this
                // null is nested in (heuristic: the closest previous line
                // that starts a modifier chain OR an Icon/Image call).
                var stmtStart = index
                var depthBack = 0
                var j = index
                while (j > 0 && j > index - 12) {
                    val prev = lines[j - 1]
                    if (depthBack == 0 && (modifierChainStart.containsMatchIn(prev) ||
                            Regex("""\bIcon\(|\bImage\(""").containsMatchIn(prev))
                    ) {
                        stmtStart = j - 1
                        break
                    }
                    // Track simple brace/paren balance while walking back.
                    depthBack += prev.count { it == ')' || it == '}' } -
                        prev.count { it == '(' || it == '{' }
                    if (depthBack < 0) {
                        // This line closed a region the null is inside.
                        stmtStart = j - 1
                        depthBack = 0
                    }
                    j -= 1
                }
                // Forward: the statement's extent — walk until the depth
                // (opened minus closed) returns to zero past the start,
                // capped at 14 lines.
                var depth = 0
                var stmtEnd = index
                for (k in stmtStart until minOf(stmtStart + 14, lines.size)) {
                    depth += lines[k].count { it == '(' || it == '{' } -
                        lines[k].count { it == ')' || it == '}' }
                    if (depth <= 0 && k >= index) {
                        stmtEnd = k
                        break
                    }
                    stmtEnd = k
                }
                val statement = lines.subList(stmtStart, stmtEnd + 1).joinToString("\n")
                // The statement must BE the clickable node's own chain:
                // the modifier chain starts it, and the null shares it.
                val startsChain = modifierChainStart.containsMatchIn(lines[stmtStart])
                if (startsChain && clickable.containsMatchIn(statement) &&
                    nullDesc.containsMatchIn(statement)
                ) {
                    hits.add(Triple(file.name, index + 1, line.trim()))
                }
            }
        }
        return hits
    }

    /**
     * KNOWN EXCEPTIONS for the clickable-hidden-description scan, with
     * reasons. Format: `"FileName.kt:lineNumber" to "reason"`. EDITABLE:
     * when a reviewed finding is accepted, add its key here.
     */
    private val hiddenClickExceptions: Map<String, String> = mapOf(
        // (none on the audited tree — every `contentDescription = null`
        //  near a clickable is a decorative glyph INSIDE the labelled
        //  container, which the narrow scan does not match.)
    )

    @Test
    fun clickables_do_not_hide_their_own_description() {
        val findings = clickableWithHiddenDescription()
        val unlisted = findings.filter { key(it.first, it.second) !in hiddenClickExceptions }
        assertTrue(
            "clickable nodes with contentDescription = null on the same modifier chain " +
                "(add reviewed entries to hiddenClickExceptions with a reason):\n" +
                unlisted.joinToString("\n") { "  ${key(it.first, it.second)}  ${it.third}" },
            unlisted.isEmpty(),
        )
    }

    // ---- scan 2: labelled-icon coverage for icon-only clickables ------------

    /**
     * Every `IconButton`/`IconToggleButton` call site must give its label
     * one of this codebase's ways: a non-null `contentDescription`
     * somewhere in the node — the Icon's own NAMED argument
     * (`contentDescription = ...`), the Icon's POSITIONAL second argument
     * (`Icon(painter, "label")`), or a
     * `Modifier.semantics { contentDescription = ... }` on the button —
     * or an exception-list entry with a reason.
     *
     * The scan reports an icon-button call whose 24-line window contains
     * no non-null label. The window spans the button's whole node:
     * content slots (a Column with icon + Text label, like the timeline's
     * OperationButton) put the label a few lines past the call, and the
     * play/pause pair sits ~20 lines deep (two branches).
     */
    private fun iconButtonsWithoutLabel(): List<Triple<String, Int, String>> {
        val buttonCall = Regex("""\bIconButton\(|\bIconToggleButton\(""")
        // Named non-null contentDescription, OR a positional string
        // label argument on the Icon call's second position, OR a Text
        // inside the content slot (the button's own visible copy merges
        // into the node for screen readers — OperationButton's shape).
        // The `\S` after the null-lookahead is the reviewer's fix: the
        // bare lookahead backtracks and matches AT the empty position,
        // so `contentDescription = null` itself read as "label present";
        // a guarded form cannot.
        val namedLabel = Regex("""contentDescription\s*=\s*(?!null\b)\S""")
        val positionalLabel = Regex("""Icon\(\s*[A-Za-z_][^,]*,\s*(stringResource|null)[^)]*\)""")
        val textContent = Regex("""\bText\(""")
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (buttonCall.containsMatchIn(line)) {
                    val from = index
                    val to = minOf(index + 24, lines.size)
                    val chunk = lines.subList(from, to).joinToString("\n")
                    val named = namedLabel.containsMatchIn(chunk)
                    val positional = positionalLabel.findAll(chunk).any { m ->
                        !m.groupValues[1].equals("null")
                    }
                    val hasText = textContent.containsMatchIn(chunk)
                    if (!named && !positional && !hasText) {
                        hits.add(Triple(file.name, index + 1, line.trim()))
                    }
                }
            }
        }
        return hits
    }

    /** KNOWN EXCEPTIONS for the icon-button label scan. */
    private val iconLabelExceptions: Map<String, String> = mapOf(
        // (none — every IconButton/IconToggleButton on the audited tree
        //  carries a label: the Icon's own contentDescription or a
        //  semantics block on the button.)
    )

    @Test
    fun icon_buttons_carry_a_label() {
        val findings = iconButtonsWithoutLabel()
        val unlisted = findings.filter { key(it.first, it.second) !in iconLabelExceptions }
        assertTrue(
            "IconButton/IconToggleButton without a non-null contentDescription in its node " +
                "(add reviewed entries to iconLabelExceptions with a reason):\n" +
                unlisted.joinToString("\n") { "  ${key(it.first, it.second)}  ${it.third}" },
            unlisted.isEmpty(),
        )
    }

    // ---- scan 3: fixed touch targets below 48dp -----------------------------

    /**
     * Fixed-size CLICKABLE nodes below the 48dp minimum. The scan keys on
     * the clickable node's OWN fixed size — a `Modifier.size(N.dp)` whose
     * line sits on a `clickable`/`combinedClickable`/`selectable`/
     * `toggleable` CHAIN (the same statement as the click wiring) — and
     * flags dimensions below 48dp.
     *
     * FALSE-POSITIVE CLASS (deliberately out of the scan's shape): small
     * fixed ICON GLYPH sizes (16–30dp) inside stock Material3 clickables.
     * A glyph size line sits inside the button's content lambda, on a
     * DIFFERENT statement than the click wiring; Material3 inflates the
     * clickable node itself through LocalMinimumInteractiveComponentSize
     * to the 48dp minimum touch target, so a small glyph never shrinks
     * the target. The scan requires the size call to share the line (or
     * window-start) of the node's own modifier chain to stay on the
     * CLICKABLE node, not the glyph.
     */
    private fun undersizedFixedClickables(): List<Triple<String, Int, String>> {
        val chainClick = Regex(
            """\.clickable\b|\.combinedClickable\b|\.selectable\b|\.toggleable\b"""
        )
        val chainSize = Regex("""\.size\(\s*(\d+(?:\.\d+)?)\s*(?:\.dp)?\s*[,)]""")
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                // The clickable's own modifier chain: size and click wiring
                // written on the same statement (multi-line chains are
                // covered by checking the 8-line window BEFORE the click
                // line for the node's own size).
                val ownChainUndersized = if (chainSize.containsMatchIn(line)) {
                    chainSize.findAll(line).any { m ->
                        val value = m.groupValues[1].toFloatOrNull() ?: return@any false
                        value in 1f..47.99f
                    } && chainClick.containsMatchIn(line)
                } else {
                    false
                }
                if (ownChainUndersized) {
                    hits.add(Triple(file.name, index + 1, line.trim()))
                }
                // A click wiring on this line: the node's own size may sit
                // a few lines EARLIER in the same chain (size before click).
                if (chainClick.containsMatchIn(line)) {
                    val from = maxOf(0, index - 8)
                    val chunk = lines.subList(from, index + 1).joinToString("\n")
                    val sizes = chainSize.findAll(chunk)
                    val undersized = sizes.any { m ->
                        val value = m.groupValues[1].toFloatOrNull() ?: return@any false
                        value in 1f..47.99f
                    }
                    if (undersized) {
                        val sizeLine = lines.subList(from, index + 1)
                            .first { chainSize.containsMatchIn(it) }
                        val sizeLineNumber = from + lines.subList(from, index + 1).indexOfFirst { chainSize.containsMatchIn(it) } + 1
                        val entry = Triple(file.name, sizeLineNumber, sizeLine.trim())
                        if (entry !in hits) hits.add(entry)
                    }
                }
            }
        }
        return hits
    }

    /** KNOWN EXCEPTIONS for the undersized-target scan. */
    private val undersizedExceptions: Map<String, String> = mapOf(
        // ScopeStrip.kt's ScopeIconButton: IconButton fixed at Modifier
        // .size(40.dp) — Material3's LocalMinimumInteractiveComponentSize
        // inflates the actual touch target to the 48dp minimum, so the
        // fixed 40dp is a VISUAL frame choice, not a touch-target cut.
        // Reported in the G6 report for the owner's device pass; kept as
        // a reasoned exception, not a violation.
        "ScopeStrip.kt:485" to "IconButton.size(40dp): M3 minimum-interactive inflates the touch target to 48dp; visual frame only",
    )

    @Test
    fun fixed_clickable_targets_reach_48dp() {
        val findings = undersizedFixedClickables()
        val unlisted = findings.filter { key(it.first, it.second) !in undersizedExceptions }
        assertTrue(
            "fixed-size clickable nodes below 48dp (Material3 inflates STOCK components, " +
                "so only custom fixed-size clickable boxes are real; add reviewed entries " +
                "to undersizedExceptions with a reason):\n" +
                unlisted.joinToString("\n") { "  ${key(it.first, it.second)}  ${it.third}" },
            unlisted.isEmpty(),
        )
    }

    // ---- scan 4: hardcoded user-visible Text literals -----------------------

    /**
     * `Text(text = "...")` (or positional `Text("...")`) with a string
     * literal argument — user-visible copy that bypasses stringResource
     * and so can never be localized. Scanned everywhere under ui/, so
     * preview-only files (SettingsComponentsPreview) surface too; those
     * belong on the exception list with their reason, while REAL screens
     * must use stringResource.
     *
     * Not flagged (reasoned, out of the regex's shape): Text with
     * interpolated non-literal arguments, Text(stringResource(...)), and
     * non-Text literals (content descriptions always resolve through
     * stringResource in this codebase).
     */
    private fun hardcodedTextLiterals(): List<Triple<String, Int, String>> {
        // Plain (non-raw) string literals: a raw string cannot end with a
        // quote right before its closing delimiter, and both patterns do.
        val named = Regex("\\bText\\(\\s*text\\s*=\\s*\"")
        val positional = Regex("\\bText\\(\\s*\"")
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                val isLiteral = named.containsMatchIn(code) || positional.containsMatchIn(code)
                if (isLiteral) {
                    hits.add(Triple(file.name, index + 1, line.trim()))
                }
            }
        }
        return hits
    }

    /** KNOWN EXCEPTIONS for the hardcoded-literal scan. */
    private val hardcodedTextExceptions: Map<String, String> = mapOf(
        // Preview-only scaffold labels: SettingsComponentsPreview.kt is a
        // @Preview gallery (never reachable from the app UI), so its
        // English section labels are not user-visible copy.
        "SettingsComponentsPreview.kt:52" to "@Preview-only scaffold label; never rendered in the app",
        "SettingsComponentsPreview.kt:89" to "@Preview-only scaffold label; never rendered in the app",
        "SettingsComponentsPreview.kt:108" to "@Preview-only scaffold label; never rendered in the app",
        // Timecode separators (EditorTransport): ':' and ' / ' are
        // punctuation of a numeric readout (00:00:12 / 00:00:30) — not
        // localizable words; every locale reads the same glyph.
        "EditorTransport.kt:248" to "timecode ':' separator of a numeric readout; not localizable copy",
        "EditorTransport.kt:262" to "timecode ' / ' separator of a numeric readout; not localizable copy",
        // ThemeSlider's Custom-slot glyph: a single-letter visual glyph
        // ("C" = Custom) drawn beside the icons for Light/System/Dark;
        // the slot's NAME reaches screen readers through the slider's
        // own semantics (stateDescription), not through this glyph.
        "ThemeSlider.kt:160" to "single-letter visual glyph for the Custom slot; the slider's stateDescription carries the name",
        // RenameClipDialog's read-only extension chip: the file's own
        // extension (".mcraw") with a leading dot — a filename fact, not
        // copy; no locale renders it differently.
        "RenameClipDialog.kt:98" to "the file's own extension ('.'+ext) — a filename fact, not copy",
    )

    @Test
    fun user_visible_text_is_not_a_hardcoded_literal() {
        val findings = hardcodedTextLiterals()
        val unlisted = findings.filter { key(it.first, it.second) !in hardcodedTextExceptions }
        assertTrue(
            "Text(...) with a hardcoded string literal (use stringResource; add reviewed " +
                "entries to hardcodedTextExceptions with a reason):\n" +
                unlisted.joinToString("\n") { "  ${key(it.first, it.second)}  ${it.third}" },
            unlisted.isEmpty(),
        )
    }

    // ---- scan 5: empty-state copy inventory (presence, per screen) ---------

    /**
     * The empty/error-state copy INVENTORY: every empty-state string the
     * UI declares must be WIRED — referenced from the ui/ sources. An
     * orphaned empty-state string (declared but never rendered) means a
     * screen's designed empty state is dead code and the screen may be
     * silently showing nothing.
     *
     * This is a PRESENCE audit (list-level): the report table carries the
     * per-screen verdicts. The scan direction is
     * strings.xml -> ui sources, the inverse of a usage audit.
     */
    private fun resRoot(): File {
        val suffixes = listOf(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.isFile) {
                    // candidate = .../src/main/res/values/strings.xml:
                    // values -> res is parentFile.parentFile.
                    return candidate.parentFile.parentFile
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the res root; tried " +
                candidates.joinToString(", ") { it.absolutePath },
        )
    }

    /**
     * KNOWN EXCEPTIONS for the empty-state wiring audit: empty-state
     * strings declared in strings.xml that are deliberately NOT rendered
     * today, each with its reason. EDITABLE: when a reviewed finding is
     * accepted, move its key OFF this list (the string then must be
     * wired) or remove the string.
     */
    private val emptyStateWiringExceptions: Map<String, String> = mapOf(
        // Designed copy for the editor's access-lost surface, but the
        // accessLost branch renders the LIBRARY's own access-lost line
        // (library_row_access_lost) as its body instead — the pair was
        // split in an earlier pass and only the title survived in use.
        // Reported to the owner (G6): either wire this body under the
        // title (a text-only addition, owner's device pass first) or
        // delete the orphaned string. Wiring it is a UI copy change, so
        // the night pass leaves the choice to the owner.
        "editor_not_loaded_body" to "orphaned: accessLost renders library_row_access_lost as the body; owner decides wire-or-delete",
    )
    private val emptyStateKeys: List<String> = listOf(
        "library_empty_title", "library_empty_body",
        "library_folder_browser_empty",
        "exports_empty_title", "exports_empty_body",
        "projects_empty_title", "projects_empty_body",
        "timeline_empty", "timeline_add_empty",
        "nodes_empty", "node_lut_empty", "editor_presets_empty",
        "settings_codec_measure_empty",
        "editor_preview_device_pending", "editor_preview_preparing",
        "editor_not_loaded_title", "editor_not_loaded_body",
        "library_row_access_lost", "library_row_grant_access",
        "library_row_file_missing",
        "library_import_failed_access",
        "library_import_failed_storage",
        "library_import_failed_unknown",
        "library_relink_failed_mismatch",
        "library_relink_failed_unknown",
    )

    @Test
    fun every_empty_state_string_is_wired_into_the_ui() {
        val sources = uiSources().joinToString("\n") { it.readText() }
        val missing = emptyStateKeys
            .filter { "R.string.$it" !in sources }
            .filter { it !in emptyStateWiringExceptions }
        assertTrue(
            "empty-state strings declared in strings.xml but never referenced from ui/ " +
                "(the designed empty state would never render):\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    // ---- scan 6: en/tr key parity at the resource level ---------------------

    /**
     * en+tr parity LOCK at the resource level (the E6 parity-lock branch
     * carries the deeper parity work; this scan is the static floor that
     * already holds on main and must keep holding): every string key in
     * values/strings.xml exists in values-tr/strings.xml with the same
     * count, and vice versa. UI work that adds a string to one file only
     * goes red here.
     */
    @Test
    fun en_and_tr_string_keys_stay_in_parity() {
        val res = resRoot()
        fun keys(dirName: String): Set<String> {
            val file = File(res, "$dirName/strings.xml")
            assertTrue("missing $dirName/strings.xml", file.isFile)
            val text = file.readText()
            return Regex("<string\\s+name=\"([a-z0-9_]+)\"")
                .findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] }
        }
        val en = keys("values")
        val tr = keys("values-tr")
        val onlyEn = en - tr
        val onlyTr = tr - en
        assertTrue(
            "en/tr string key parity broken; only in en: ${onlyEn.sorted()}, " +
                "only in tr: ${onlyTr.sorted()}",
            onlyEn.isEmpty() && onlyTr.isEmpty(),
        )
        // The audited empty-state keys above are part of the parity floor.
        val missingFromTr = emptyStateKeys.filter { it !in tr }
        assertTrue("empty-state keys missing from tr: $missingFromTr", missingFromTr.isEmpty())
    }

    // ---- scan 7: clickable rows/cards carry a label or text -----------------

    /**
     * Plain `.clickable` call sites (raw rows/cards, not stock
     * components) must carry a label one of three ways within their
     * 16-line window: a `Text(...)` (the row's own visible copy — TalkBack
     * merges the row's text), an `onClickLabel = `, or a
     * `semantics { ... contentDescription` block, or an exception entry.
     *
     * CALL-SITE SHAPES (reviewer finding): the codebase writes BOTH
     * `.clickable { ... }` (trailing lambda, NO argument parens) and
     * `.clickable(role = ...) { ... }` (parens + trailing lambda). The
     * detector therefore makes the parens OPTIONAL —
     * `\.clickable\s*(?:\([^)]*\))?\s*\{` — so neither shape escapes. The
     * original shipped form (literal `(` required) matched ZERO call sites
     * on the whole tree: the scan body never ran and its pass was vacuous
     * (proven by the reviewer's unlabeled-probe mutation going green).
     *
     * The label lookaheads carry the same backtracking trap the reviewer
     * flagged on scan-2: `onClickLabel\s*=\s*(?!null)` accepts
     * `onClickLabel = null` itself (the lookahead matches at the empty
     * position before "null"), so a row whose ONLY affordance is an
     * explicitly-nulled onClickLabel would read as labelled. The `\S`
     * guard closes that blind spot; `label\s*=` (the plain generic form)
     * is kept because no call site uses a `label = null` argument.
     *
     * This is the widest and noisiest scan; its exception list carries the
     * reasoned cases (image-tile rows whose label is the row's merged
     * content, selection cards whose checkbox state reads, etc.).
     */
    private fun plainClickablesWithoutAnyLabel(): List<Triple<String, Int, String>> {
        val plainClickable = Regex("""\.clickable\s*(?:\([^)]*\))?\s*\{""")
        val textContent = Regex(
            """\bText\(|headlineContent|label\s*=|onClickLabel\s*=\s*(?!null\b)\S"""
        )
        val semanticsLabel = Regex("""semantics\s*\{""")
        val hits = mutableListOf<Triple<String, Int, String>>()
        for (file in uiSources()) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (plainClickable.containsMatchIn(line)) {
                    val from = maxOf(0, index - 8)
                    val to = minOf(index + 16, lines.size)
                    val chunk = lines.subList(from, to).joinToString("\n")
                    if (!textContent.containsMatchIn(chunk) && !semanticsLabel.containsMatchIn(chunk)) {
                        hits.add(Triple(file.name, index + 1, line.trim()))
                    }
                }
            }
        }
        return hits
    }

    /** KNOWN EXCEPTIONS for the plain-clickable label scan. */
    private val plainClickableExceptions: Map<String, String> = mapOf(
        // AppNav's gyro-ambiguous candidate rows: each ListItem's
        // headlineContent Text(doc.displayName) sits INSIDE the trailing
        // lambda after the clickable chain — the scan window cuts off
        // before it; the row IS labelled (the file's name).
        "AppNav.kt:2674" to "gyro-ambiguous candidate ListItem; the headline Text(doc.displayName) follows past the window",
        // The DONE export card's two tap wirings (select-mode toggle /
        // open): the clickable rides the CARD modifier while the label —
        // the source filename headline Text — sits in the card's content
        // lambda ~14 lines below, past the window. TalkBack merges the
        // card's texts into the row focus (the reviewer-probed same
        // pattern), so the node is labelled at runtime; the entries keep
        // the LIVE scan honest about the two shapes it sees.
        // (merged M3+F+I5) the DONE export card's tap wirings moved with
        // the merged file (539/541 → 635/637) and the LIVE + ARCHIVE card
        // shapes (761/763, 1100) carry the same reviewed pattern: the
        // clickable rides the CARD modifier while the label — the row's
        // filename headline Text — sits in the card's content lambda past
        // the window; TalkBack merges the card's texts into the row focus.
        // (M8, 2026-11-27) the dialog additions moved the rows again
        // (635/637 → 781/783, 761/763 → 907/909, 1100 → 1246): the same
        // reviewed shapes, renumbered to the live file.
        // (M10, 2026-11-27) the remembered-checkbox state notes moved the
        // rows once more (781/783 → 825/827, 907/909 → 951/953,
        // 1246 → 1290): the same reviewed shapes, renumbered to the live
        // file.
        "ExportsScreen.kt:825" to "DONE export card, select-mode toggle branch: the card's filename headline Text (content lambda) labels the merged row focus",
        "ExportsScreen.kt:827" to "DONE export card, open branch: the card's filename headline Text (content lambda) labels the merged row focus",
        "ExportsScreen.kt:951" to "DONE export card (thumbnail shape), select-mode toggle branch: the card's filename headline Text labels the merged row focus",
        "ExportsScreen.kt:953" to "DONE export card (thumbnail shape), open branch: the card's filename headline Text labels the merged row focus",
        "ExportsScreen.kt:1290" to "FAILED export card, select-mode toggle branch: the card's filename headline Text labels the merged row focus (same reviewed pattern)",
    )

    @Test
    fun plain_clickables_carry_text_or_label() {
        val findings = plainClickablesWithoutAnyLabel()
        val unlisted = findings.filter { key(it.first, it.second) !in plainClickableExceptions }
        assertTrue(
            ".clickable rows/cards with no Text, onClickLabel or semantics label in their node " +
                "(add reviewed entries to plainClickableExceptions with a reason):\n" +
                unlisted.joinToString("\n") { "  ${key(it.first, it.second)}  ${it.third}" },
            unlisted.isEmpty(),
        )
    }

    // ---- inventory report helper (informational, not a gate) ---------------

    /**
     * The DECORATIVE inventory: `contentDescription = null` occurrences,
     * reported for the G6 table. This is a COUNT test (the number is
     * pinned so a silent drift is noticed and re-reviewed; the pinned
     * value moves by editing this test when the UI grows).
     */
    @Test
    fun decorative_null_descriptions_inventory_count_is_pinned() {
        val count = scan(Regex("""contentDescription\s*=\s*null""")).size
        assertEquals(
            "decorative contentDescription = null count drifted from the audited baseline; " +
                "re-review the new occurrences (decorative is usually CORRECT — the count " +
                "just pins the inventory for the report)",
            DECORATIVE_NULL_COUNT,
            count,
        )
    }

    private fun scan(pattern: Regex, unused: Int): List<Triple<String, Int, String>> = scan(pattern)

    companion object {
        /** Audited on the test-static-a11y-audit tree (main fb0f80d6): 50. */
        // (merged F+I5) CinemaDngSection's two decorative icons (folder
        // glyph beside the folder-ROW's own Text; warning glyph whose
        // verdict text follows in the same line) — reviewed, decorative.
        const val DECORATIVE_NULL_COUNT = 53
    }
}
