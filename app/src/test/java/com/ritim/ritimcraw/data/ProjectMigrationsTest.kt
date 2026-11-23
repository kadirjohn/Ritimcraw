package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectMigrationsTest {

    private fun root(version: Any?, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        (if (version == null) emptyMap() else mapOf("version" to version)) + extra

    /** A test step that tags the root so order/application is observable. */
    private fun tag(name: String): MigrationStep = { r ->
        r + ("trail" to ((r["trail"] as? String ?: "") + name))
    }

    @Test
    fun shippedChainIsEmptyAndCurrentIsOne() {
        assertTrue(ProjectMigrations.MIGRATIONS.isEmpty())
        assertEquals(1, ProjectMigrations.CURRENT_VERSION)
    }

    @Test
    fun missingOrInvalidVersionIsSchemaOne() {
        assertEquals(1, ProjectMigrations.versionOf(root(null)))
        assertEquals(1, ProjectMigrations.versionOf(root("two")))
        assertEquals(1, ProjectMigrations.versionOf(root(0.0)))
        assertEquals(1, ProjectMigrations.versionOf(root(-3.0)))
        assertEquals(3, ProjectMigrations.versionOf(root(3.0)))
    }

    @Test
    fun currentVersionPassesThroughUntouched() {
        val r = root(1.0, mapOf("grades" to mapOf<String, Any?>()))
        val res = ProjectMigrations.migrate(r, emptyList()) as ProjectMigrations.Result.Ok
        assertEquals(r, res.root)
        assertFalse(res.migrated)
        assertEquals(1, res.fromVersion)
    }

    @Test
    fun chainRunsInOrderFromTheFileVersionAndStampsEachVersion() {
        val chain = listOf(tag("a"), tag("b"), tag("c")) // v1->2, v2->3, v3->4
        val fromV1 = ProjectMigrations.migrate(root(1.0), chain) as ProjectMigrations.Result.Ok
        assertEquals("abc", fromV1.root["trail"])
        assertEquals(4.0, fromV1.root["version"])
        assertTrue(fromV1.migrated)

        val fromV3 = ProjectMigrations.migrate(root(3.0), chain) as ProjectMigrations.Result.Ok
        assertEquals("c", fromV3.root["trail"]) // only the last step
        assertEquals(4.0, fromV3.root["version"])

        val unversioned = ProjectMigrations.migrate(root(null), chain) as ProjectMigrations.Result.Ok
        assertEquals("abc", unversioned.root["trail"]) // missing = v1
    }

    @Test
    fun sloppyStepCannotLeaveAStaleVersion() {
        val stale: MigrationStep = { r -> r + ("version" to 1.0) }
        val res = ProjectMigrations.migrate(root(1.0), listOf(stale)) as ProjectMigrations.Result.Ok
        assertEquals(2.0, res.root["version"])
    }

    @Test
    fun newerThanSupportedIsRejectedAndNoStepRuns() {
        var ran = false
        val chain = listOf<MigrationStep>({ ran = true; it })
        val res = ProjectMigrations.migrate(root(3.0), chain)
        assertEquals(ProjectMigrations.Result.TooNew(3, 2), res)
        assertFalse(ran)
    }

    @Test
    fun migrationFailureIsNotSwallowed() {
        val boom: MigrationStep = { throw IllegalStateException("bad step") }
        try {
            ProjectMigrations.migrate(root(1.0), listOf(boom))
            fail("expected the step's exception")
        } catch (e: IllegalStateException) {
            assertEquals("bad step", e.message)
        }
    }
}
