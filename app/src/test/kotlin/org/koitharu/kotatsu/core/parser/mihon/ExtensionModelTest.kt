package org.zeravrix.kotatsu.extension.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionModelTest {

    private val sampleStore = ExtensionStore(
        indexUrl = "https://example.com/repo/index.min.json",
        name = "Official Store",
        badgeLabel = "Official",
        signingKey = "SIG_KEY_123",
    )

    private val availableOld = Extension.Available(
        name = "Team X",
        pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
        versionName = "1.6.30",
        versionCode = 30L,
        libVersion = 1.6,
        lang = "ar",
        contentWarning = ContentWarning.SAFE,
        sources = emptyList(),
        apkUrl = "https://example.com/teamx-30.apk",
        iconUrl = "https://example.com/teamx.png",
        store = sampleStore,
    )

    private val availableNew = Extension.Available(
        name = "Team X",
        pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
        versionName = "1.6.35",
        versionCode = 35L,
        libVersion = 1.6,
        lang = "ar",
        contentWarning = ContentWarning.SAFE,
        sources = emptyList(),
        apkUrl = "https://example.com/teamx-35.apk",
        iconUrl = "https://example.com/teamx.png",
        store = sampleStore,
    )

    @Test
    fun `detects available update when store has newer versionCode`() {
        val installed = Extension.Loaded(
            name = "Team X",
            pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
            versionName = "1.6.30",
            versionCode = 30L,
            libVersion = 1.6,
            lang = "ar",
            contentWarning = ContentWarning.SAFE,
            isShared = false,
            signatures = listOf("SIG_KEY_123"),
            pkgFactory = null,
            sources = emptyList(),
        )

        val update = installed.findUpdate(listOf(availableOld, availableNew))
        assertNotNull(update)
        assertEquals(35L, update?.versionCode)
    }

    @Test
    fun `does not report update when installed version is up to date`() {
        val installed = Extension.Loaded(
            name = "Team X",
            pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
            versionName = "1.6.35",
            versionCode = 35L,
            libVersion = 1.6,
            lang = "ar",
            contentWarning = ContentWarning.SAFE,
            isShared = false,
            signatures = listOf("SIG_KEY_123"),
            pkgFactory = null,
            sources = emptyList(),
        )

        val update = installed.findUpdate(listOf(availableOld, availableNew))
        assertNull(update)
    }

    @Test
    fun `rejects listing if signature does not match store key`() {
        val installedWithWrongKey = Extension.Loaded(
            name = "Team X",
            pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
            versionName = "1.6.30",
            versionCode = 30L,
            libVersion = 1.6,
            lang = "ar",
            contentWarning = ContentWarning.SAFE,
            isShared = false,
            signatures = listOf("DIFFERENT_UNTRUSTED_KEY"),
            pkgFactory = null,
            sources = emptyList(),
        )

        val listing = installedWithWrongKey.findListing(listOf(availableNew))
        assertNull(listing)
    }
}
