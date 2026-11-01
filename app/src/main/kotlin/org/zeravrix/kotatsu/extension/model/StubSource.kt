package org.zeravrix.kotatsu.extension.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/**
 * A placeholder source used when an extension is uninstalled or temporarily unavailable,
 * preventing library manga entries and histories from crashing.
 */
data class StubSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
) : Source {

    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage =
        throw SourceNotInstalledException(id, name)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        throw SourceNotInstalledException(id, name)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw SourceNotInstalledException(id, name)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw SourceNotInstalledException(id, name)

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw SourceNotInstalledException(id, name)

    override fun toString(): String =
        if (name.isNotBlank()) "$name (${lang.uppercase()})" else id.toString()

    companion object {
        fun from(source: Source): StubSource =
            StubSource(id = source.id, lang = source.lang, name = source.name)
    }
}

class SourceNotInstalledException(val sourceId: Long, val sourceName: String) :
    Exception("Source '$sourceName' (id: $sourceId) is not installed or enabled")
