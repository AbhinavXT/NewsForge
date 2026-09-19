package com.abhinavxt.newsforge.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the exported Room schemas.
 *
 * A migration can only be tested against the schema it started from, so a version with no
 * exported JSON is a hop that is permanently untestable — and the gap is silent, because
 * nothing fails at build time and the app runs fine until somebody upgrades from exactly
 * that version. Two are already missing, which is how this test came to exist.
 *
 * Plain JVM, so it runs on every `test` invocation rather than needing a device. It checks
 * the shape of the export rather than the correctness of any migration; that is
 * `MigrationTest`, which needs an emulator and cannot cover the versions this test finds
 * missing.
 */
class SchemaExportTest {

    private val directory = File("schemas/com.abhinavxt.newsforge.data.db.NewsForgeDatabase")

    private fun exportedVersions(): List<Int> =
        directory.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .sorted()

    @Test
    fun schemasAreExportedAtAll() {
        // Without `room.schemaLocation` the directory is empty and every migration in the
        // app is untestable — a state worth failing loudly for rather than discovering
        // when one goes wrong.
        assertTrue("No exported schemas in $directory", directory.isDirectory)
        assertTrue("No schema JSON files exported", exportedVersions().isNotEmpty())
    }

    @Test
    fun everyVersionUpToTheNewestIsExported() {
        val versions = exportedVersions()
        val expected = (1..versions.last()).toList()
        val missing = expected - versions.toSet()
        assertEquals(
            "Schema JSONs missing for version(s) $missing — the migrations into and out " +
                "of them cannot be tested. Re-export by checking out the commit that " +
                "introduced each and building, or accept the gap deliberately.",
            emptyList<Int>(),
            missing,
        )
    }

    @Test
    fun eachExportDeclaresTheVersionItsFilenameClaims() {
        // A file copied from another version is worse than a missing one: the migration
        // test would pass against a schema that never shipped.
        for (version in exportedVersions()) {
            val text = File(directory, "$version.json").readText()
            assertTrue(
                "$version.json does not declare version $version",
                Regex("\"version\"\\s*:\\s*$version\\b").containsMatchIn(text),
            )
        }
    }
}
