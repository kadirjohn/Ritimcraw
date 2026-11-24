package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The single entry gate for `files/project.json` (color model v2 plan §6.4,
 * F1-2). One call at app start, BEFORE any store reads or writes the file:
 *
 *  - The file is read once and its schema version determined
 *    ([ProjectMigrations.versionOf]: missing/invalid = 1).
 *  - NEWER than [ProjectMigrations.CURRENT_VERSION] ([Outcome.TooNew]):
 *    the MIGRATIONS chain does NOT run, the file is NOT touched, and the
 *    whole process goes globally READ-ONLY — every store write becomes an
 *    observable no-op ([readOnly]).
 *  - OLDER than CURRENT_VERSION — only possible once a real migration
 *    step exists; today [ProjectMigrations.MIGRATIONS] is empty — a
 *    TIMESTAMPED BACKUP of the unmigrated bytes is taken first (K4: it is
 *    never deleted, never overwritten, not even by a later migration), the
 *    chain runs in this ONE place, and the migrated root is atomically
 *    written back ([Outcome.Migrated]).
 *  - Already CURRENT ([Outcome.Open]): the normal path; stores read and
 *    write as before.
 *
 * Why a gate at all (plan §6.5's "critical finding"): each store merges by
 * re-rendering the file with only its own section replaced, so a store
 * that writes on a file it cannot interpret would silently corrupt data
 * the next migration touches. Today only GradeStore knows about TooNew;
 * with the gate the knowledge is process-global and every write path can
 * be held to it. The GRADE-specific notification GradeStore offers
 * (its `rejectedNewerVersion` property) is unchanged; the gate is what
 * informs it, not the other way around.
 *
 * MIGRATION STANCE: the chain runs in exactly one sanctioned place — here.
 * [GradeStore.parseGradesMigrated] keeps accepting a chain parameter ONLY
 * as the test seam it already was (its own load path passes the shipped
 * chain, which currently migrates nothing); when a real step lands
 * (F1-3), `open` is the only production caller that runs it.
 */
object ProjectFile {

    /** What `open` found and did. */
    sealed class Outcome {
        /** Absent file, corrupt file, or already at CURRENT_VERSION. */
        data object Open : Outcome()

        /**
         * A real step ran (file version < CURRENT_VERSION): the chain has
         * been applied and atomically persisted, and [backup] holds the
         * pre-migration copy written before anything changed.
         */
        data class Migrated(val fromVersion: Int, val backup: File) : Outcome()

        /**
         * The file was written by a NEWER build. Nothing was written and
         * nothing will be: the process is read-only from here on.
         */
        data class TooNew(val fileVersion: Int) : Outcome()
    }

    /**
     * The process-global read-only verdict. Set once by [open] when the
     * file is TooNew; stores consult this through [writeTextIfWritable]
     * BEFORE writing, so a newer file is never stamped down and never
     * has its sections re-rendered by a build that cannot read them.
     */
    @Volatile
    private var tooNew: Int? = null

    /** True when [open] rejected the file as newer than this build. */
    val readOnly: Boolean get() = tooNew != null

    /** The rejected file's version, or null when the file is ours. */
    val rejectedVersion: Int? get() = tooNew

    /** Test-only: resets the gate between cases (no production caller). */
    internal fun resetForTest() {
        tooNew = null
    }

    /**
     * The gate. Read the file once, decide its version, back up + migrate
     * if older, go read-only if newer. Absent or corrupt files open as
     * [Outcome.Open]: a corrupt file is each store's own fallback problem
     * today (they degrade to defaults and the raw bytes stay untouched),
     * and the gate must not add a new failure mode of its own.
     *
     * @param nowMillis the timestamp stamped into a backup's name; a
     * parameter rather than a clock call so tests are deterministic.
     * @param migrations the chain to run, a parameter so the K4 backup
     * path is provable under TEST with a fake step (the shipped
     * [ProjectMigrations.MIGRATIONS] stays empty until F1-3; production
     * callers never pass one).
     */
    fun open(
        file: File,
        nowMillis: Long = System.currentTimeMillis(),
        migrations: List<MigrationStep> = ProjectMigrations.MIGRATIONS,
    ): Outcome {
        tooNew = null
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val root = text?.let {
            runCatching { parseRoot(it) }.getOrNull()
        } ?: return Outcome.Open // absent or unreadable: not our error to make
        val current = 1 + migrations.size
        val from = ProjectMigrations.versionOf(root)
        if (from > current) {
            tooNew = from
            return Outcome.TooNew(from)
        }
        if (from == current) {
            return Outcome.Open // the common case; nothing to do
        }
        // from < CURRENT: run the chain, but keep the original bytes
        // FIRST — the backup is the rollback path (K4: never deleted).
        val backup = backupDir(file).let { dir ->
            val target = File(dir, backupName(from, nowMillis))
            // The backup itself is atomic AND never overwritten: a second
            // run in the same second (a rapid-fire reopen) appends a
            // suffix instead of clobbering an existing rescue copy.
            var candidate = target
            var n = 2
            while (candidate.exists()) {
                candidate = File(dir, candidate.name + "-$n")
                n++
            }
            candidate
        }
        AtomicFiles.writeText(backup, text!!)
        @Suppress("UNCHECKED_CAST")
        val result = ProjectMigrations.migrate(root as Map<String, Any?>, migrations)
        return when (result) {
            is ProjectMigrations.Result.TooNew -> {
                // Cannot happen: from <= current was checked above. Held
                // defensively — the chain is a parameter in tests and may
                // one day be assembled differently.
                tooNew = result.fileVersion
                Outcome.TooNew(result.fileVersion)
            }
            is ProjectMigrations.Result.Ok -> {
                if (result.migrated) {
                    // Canonical order (version first): same shape a store
                    // write produces, so the gate's file and a store's
                    // file are byte-stable against each other.
                    AtomicFiles.writeText(file, ProjectJson.renderRoot(result.root))
                    Outcome.Migrated(from, backup)
                } else {
                    // The chain decided nothing needed doing; the backup
                    // still counts (the version said a step would run).
                    Outcome.Migrated(from, backup)
                }
            }
        }
    }

    /**
     * The single write primitive every project.json store goes through:
     * atomic write when the gate allows it, an OBSERVABLE no-op when the
     * process is read-only. Returns true when the bytes landed, false
     * when the write was suppressed ([readOnly]) — the observability the
     * TooNew contract asks for; stores surface it as they see fit and
     * tests assert on it.
     */
    fun writeTextIfWritable(file: File, text: String): Boolean {
        if (readOnly) return false
        AtomicFiles.writeText(file, text)
        return true
    }

    /** False exactly when a newer build owns the project file. */
    fun writable(): Boolean = !readOnly

    // ---- backup helpers (K4) ----

    /** `files/backups`, beside project.json (same filesystem: rename-safe). */
    internal fun backupDir(project: File): File =
        File(project.parentFile ?: project.absoluteFile.parentFile, "backups")

    /**
     * `project-v<from>-<yyyyMMdd-HHmmss>.json` — the plan §6.4.2 name: the
     * schema it rescues and a sortable timestamp, no collision-prone
     * wall-clock-only name.
     */
    internal fun backupName(fromVersion: Int, nowMillis: Long): String {
        val t = java.time.Instant.ofEpochMilli(nowMillis)
        val zoned = t.atZone(java.time.ZoneId.systemDefault())
        val ymd = "%04d%02d%02d".format(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        val hms = "%02d%02d%02d".format(zoned.hour, zoned.minute, zoned.second)
        return "project-v$fromVersion-$ymd-$hms.json"
    }

    private fun parseRoot(text: String): Map<*, *> {
        val p = JsonParser(text)
        val root = p.parseValue()
        if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
        return root
    }
}
