package org.zeravrix.kotatsu.core.parser.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MihonSourceRuntimeTest {
    @Test
    fun `restores executable source from stable key`() {
        val original = MihonMangaSource(
            packageName = "eu.kanade.tachiyomi.extension.ar.teamx",
            sourceId = 123L,
            sourceName = "Team X",
            language = "ar",
        )

        assertEquals(original, MihonMangaSource.fromKey(original.name))
    }

    @Test
    fun `normalizes Mihon 1 point 6 float metadata`() {
        assertEquals(1.6, MihonSourceRuntime.normalizeExtensionApiVersion(1.6f, null)!!, 0.0)
    }

    @Test
    fun `normalizes Mihon 1 point 4 float metadata`() {
        assertEquals(1.4, MihonSourceRuntime.normalizeExtensionApiVersion(1.4f, null)!!, 0.0)
    }

    @Test
    fun `falls back to extension version name`() {
        assertEquals(1.6, MihonSourceRuntime.normalizeExtensionApiVersion(0f, "1.6.33")!!, 0.0)
        assertNull(MihonSourceRuntime.normalizeExtensionApiVersion(0f, null))
    }
}
