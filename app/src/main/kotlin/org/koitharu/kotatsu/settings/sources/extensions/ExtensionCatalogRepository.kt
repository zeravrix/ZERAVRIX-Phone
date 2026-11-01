package org.zeravrix.kotatsu.settings.sources.extensions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension

@Serializable
data class ExtensionSourceCatalogEntry(
    val sourceName: String,
    val language: String,
    val extensionName: String,
    val packageName: String,
    val apkUrl: String,
    val versionName: String,
    val versionCode: Long,
    val nsfw: Int,
    val iconUrl: String = "",
)

@Singleton
class ExtensionCatalogRepository @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ExtensionSourceCatalogEntry>> = _entries.asStateFlow()

    init {
        if (!preferences.getBoolean("repositories_seeded", false) && !preferences.contains(KEY_REPOSITORIES)) {
            preferences.edit()
                .putStringSet(KEY_REPOSITORIES, DEFAULT_REPOSITORIES)
                .putBoolean("repositories_seeded", true)
                .commit()
        }

        CoroutineScope(Dispatchers.IO).launch {
            ExtensionManager.getInstance(context).availableExtensionsFlow.collect { available ->
                if (available.isNotEmpty()) {
                    saveFromAvailable(available)
                }
            }
        }
    }

    fun saveFromAvailable(extensions: List<Extension.Available>) {
        val entries = (bundledSourceEntries() + extensions.flatMap { extension ->
            val sources = extension.sources.ifEmpty {
                listOf(
                    Extension.Available.SourceInfo(
                        id = 0,
                        lang = extension.lang,
                        name = extension.name,
                        baseUrl = "",
                    ),
                )
            }
            sources.map { source ->
                ExtensionSourceCatalogEntry(
                    sourceName = source.name.ifBlank { extension.name },
                    language = source.lang.ifBlank { extension.lang },
                    extensionName = extension.name,
                    packageName = extension.pkgName,
                    apkUrl = extension.apkUrl,
                    versionName = extension.versionName,
                    versionCode = extension.versionCode,
                    nsfw = if (extension.contentWarning == ContentWarning.NSFW) 1 else 0,
                    iconUrl = extension.iconUrl,
                )
            }
        }).distinctBy { "${it.packageName}:${it.sourceName}:${it.language}" }
            .sortedWith(compareBy({ languageSortKey(it.language) }, { it.sourceName.lowercase() }, { it.extensionName.lowercase() }))
        preferences.edit().putString(KEY_ENTRIES, json.encodeToString(entries)).apply()
        _entries.value = entries
    }

    private fun load(): List<ExtensionSourceCatalogEntry> {
        val persisted = runCatching {
            json.decodeFromString<List<ExtensionSourceCatalogEntry>>(
                preferences.getString(KEY_ENTRIES, "[]").orEmpty(),
            )
        }.getOrDefault(emptyList())
        return (bundledSourceEntries() + persisted)
            .distinctBy { "${it.packageName}:${it.sourceName}:${it.language}" }
            .sortedWith(compareBy({ languageSortKey(it.language) }, { it.sourceName.lowercase() }, { it.extensionName.lowercase() }))
    }

    private fun bundledSourceEntries(): List<ExtensionSourceCatalogEntry> = listOf(
        bundled("Arab Toons", "eu.kanade.tachiyomi.extension.ar.arabtoons", "1.4.53", false),
        bundled("Azora", "eu.kanade.tachiyomi.extension.ar.azora", "1.6.72", false),
        bundled("Dilar", "eu.kanade.tachiyomi.extension.ar.dilar", "1.6.14", false),
        bundled("Hijala", "eu.kanade.tachiyomi.extension.ar.hijala", "1.4.35", false),
        bundled("Mangalek", "eu.kanade.tachiyomi.extension.ar.mangalek", "1.4.65", false),
        bundled("MangaSpark", "eu.kanade.tachiyomi.extension.ar.mangaspark", "1.4.60", false),
        bundled("MangaSwat", "eu.kanade.tachiyomi.extension.ar.mangaswat", "1.6.61", false),
        bundled("Team X", "eu.kanade.tachiyomi.extension.ar.teamx", "1.6.33", false),
        bundled("Arabs Hentai", "eu.kanade.tachiyomi.extension.ar.arabshentai", "1.4.4", true),
    )

    private fun bundled(source: String, pkg: String, version: String, nsfw: Boolean) = ExtensionSourceCatalogEntry(
        sourceName = source,
        language = "ar",
        extensionName = "Tachiyomi: $source",
        packageName = pkg,
        apkUrl = "",
        versionName = version,
        versionCode = version.substringAfterLast('.').toLongOrNull() ?: 0L,
        nsfw = if (nsfw) 1 else 0,
    )

    private companion object {
        const val PREFERENCES_NAME = "zeravrix_extensions"
        const val KEY_ENTRIES = "catalog_source_entries"
        const val KEY_REPOSITORIES = "repositories"
        val DEFAULT_REPOSITORIES = setOf(
            "https://raw.githubusercontent.com/kitsumed/mihonyomi-extensions/releases/repo.json",
            "https://raw.githubusercontent.com/Kareadita/tach-extensions/repo/repo.json",
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
        )
    }
}

private fun languageSortKey(language: String): String = language.trim().lowercase().let {
    if (it.isBlank() || it == "all") "zzzz" else it
}
