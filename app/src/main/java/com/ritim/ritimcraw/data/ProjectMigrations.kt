package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.grade2.GradeV2Migration

/**
 * The schema migration chain for `files/project.json` (plan section 3.2,
 * the FilmCraft `format` pattern). The color model v2 package (F1-3) lands
 * the first real step: [MIGRATIONS]`[0]` folds schema 1's flat grade
 * records into the schema-2 nested shape (GradeV2Migration — grades,
 * presets, export snapshots and the inputless-clip materialization rule),
 * so [CURRENT_VERSION] is 2.
 *
 * Rules:
 *  - [MIGRATIONS]`[i]` upgrades a root object from schema `i + 1` to
 *    `i + 2`, so the chain is applied in order from the file's version.
 *  - A file with no/invalid top-level "version" is schema 1 (every file
 *    written before the version key was read back is v1).
 *  - A file NEWER than [CURRENT_VERSION] is REJECTED ([Result.TooNew]):
 *    an older build must neither reinterpret nor overwrite data it does
 *    not understand. Callers go read-only.
 *  - Migrations are pure (root map in, root map out) so they are
 *    JVM-testable; the chain is a parameter of [migrate] for tests.
 */
/** One step: root object of schema N in, root object of schema N+1 out. */
internal typealias MigrationStep = (Map<String, Any?>) -> Map<String, Any?>

internal object ProjectMigrations {

    /**
     * The shipped chain. Step 0 (v1 -> v2, F1-3): the grade schema fold
     * (GradeV2Migration.fromV1) — a hot-swap piece: tests may pass their
     * own chains that start with this step.
     */
    val MIGRATIONS: List<MigrationStep> = listOf(GradeV2Migration::fromV1)

    /** The schema version this build reads and writes. */
    val CURRENT_VERSION: Int get() = 1 + MIGRATIONS.size

    sealed class Result {
        /** [root] is at the chain's final version; [migrated] is true when any step ran. */
        data class Ok(
            val root: Map<String, Any?>,
            val fromVersion: Int,
            val migrated: Boolean,
        ) : Result()

        /** The file was written by a newer build: do not read it as ours, do not overwrite it. */
        data class TooNew(val fileVersion: Int, val supportedVersion: Int) : Result()
    }

    /** The schema version a parsed root declares (missing or invalid = 1). */
    fun versionOf(root: Map<*, *>): Int {
        val v = (root["version"] as? Double)?.toInt() ?: return 1
        return if (v < 1) 1 else v
    }

    fun migrate(
        root: Map<String, Any?>,
        migrations: List<MigrationStep> = MIGRATIONS,
    ): Result {
        val supported = 1 + migrations.size
        val from = versionOf(root)
        if (from > supported) return Result.TooNew(from, supported)
        var current: Map<String, Any?> = root
        for (i in (from - 1) until migrations.size) {
            // Each step owns its version bump; the chain re-stamps it so a
            // sloppy step can never leave a stale number behind.
            current = migrations[i](current) + ("version" to (i + 2).toDouble())
        }
        return Result.Ok(current, from, migrated = from < supported)
    }
}
