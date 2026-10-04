package com.abhinavxt.newsforge.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the one thing the destructive fallback used to paper over.
 *
 * Upgrades no longer fall back to wiping the store, so an upgrade with no migration path
 * is a crash on launch. That is only acceptable if such a build cannot exist, which is
 * what this checks: [NewsForgeDatabase.MIGRATIONS] has to step from version 1 to the
 * declared version one hop at a time, with nothing missing and nothing doubled.
 *
 * Plain JVM, so it runs with every `test`. Whether each migration produces the right
 * schema is `MigrationTest`'s job, on a device.
 */
class MigrationChainTest {

    /**
     * The newest exported schema, which KSP rewrites on every build — so it is the version
     * the `@Database` annotation declares. Read from disk because that annotation is not
     * retained at runtime.
     */
    private val declaredVersion: Int =
        File("schemas/com.abhinavxt.newsforge.data.db.NewsForgeDatabase")
            .listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .max()

    @Test
    fun migrationsStepFromOneToTheDeclaredVersion() {
        val hops = NewsForgeDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        val expected = (1 until declaredVersion).map { it to it + 1 }
        assertEquals(
            "Every version up to $declaredVersion needs exactly one migration into it; " +
                "bumping the version without adding one to MIGRATIONS crashes upgrades.",
            expected,
            hops,
        )
    }
}
