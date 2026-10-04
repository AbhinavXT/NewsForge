package com.abhinavxt.newsforge.data.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the upgrade path's SQL to what Room generates for a fresh install.
 *
 * The migration to version 19 creates the full-text table and its triggers from
 * hand-written strings. A fresh install gets Room's own from the `@Fts4` entity. If the
 * two differ in any option, an upgraded install fails schema validation on its first
 * launch after the update — and a fresh install, which is what every developer runs,
 * never shows it. This compares the strings against the schema Room exports at build
 * time, so the difference fails here instead.
 *
 * Plain text search over the JSON, like [SchemaExportTest], so it needs no parser.
 */
class ArticleSearchSchemaTest {

    private val file =
        File("schemas/com.abhinavxt.newsforge.data.db.NewsForgeDatabase/19.json")

    private fun exported(): String {
        assertTrue(
            "19.json is not exported yet — build once (./gradlew assembleDebug) so Room " +
                "writes it, then commit it",
            file.isFile,
        )
        return file.readText()
    }

    @Test
    fun theVirtualTableMatchesTheEntity() {
        // Room exports the create statement with the table name as a placeholder.
        val expected = ArticleSearchSchema.CREATE
            .replace("`${ArticleSearchSchema.TABLE}`", "`\${TABLE_NAME}`")
        assertTrue(
            "Migration CREATE differs from Room's.\n  migration: $expected\n" +
                "  look for \"createSql\" under \"article_fts\" in ${file.name}",
            exported().contains(expected),
        )
    }

    @Test
    fun theSyncTriggersMatchTheEntity() {
        val text = exported()
        for (trigger in ArticleSearchSchema.TRIGGERS) {
            assertTrue(
                "Migration trigger not found in Room's export:\n  $trigger\n" +
                    "  compare with \"contentSyncTriggers\" under \"article_fts\"",
                text.contains(trigger),
            )
        }
    }

    @Test
    fun theMigrationRunsEverythingItDeclares() {
        val statements = ArticleSearchSchema.MIGRATION
        assertTrue(statements.first() == ArticleSearchSchema.CREATE)
        assertTrue(statements.containsAll(ArticleSearchSchema.TRIGGERS))
        // Last, after the triggers exist: rebuilding first and creating triggers second
        // would leave a window in which a write was not indexed.
        assertTrue(statements.last() == ArticleSearchSchema.REBUILD)
    }
}
