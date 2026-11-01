package org.zeravrix.kotatsu.core.parser.mihon

import android.content.Context
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.zeravrix.kotatsu.core.cache.MemoryContentCache
import org.zeravrix.kotatsu.core.parser.CachingMangaRepository
import org.zeravrix.kotatsu.extension.model.StubSource

class MihonMangaRepository(
    private val context: Context,
    override val source: MihonMangaSource,
    cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

    override val cacheEnabled: Boolean = true

    override val sortOrders = setOf(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override var defaultSortOrder = SortOrder.POPULARITY

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    @Volatile private var estimatedPageSize = 0
    @Volatile private var lastOffset = -1
    @Volatile private var lastPage = 1

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
        withContext(Dispatchers.IO) {
            val loaded = MihonSourceManager.getLoadedOrAwait(context, source)
            val effectiveSourceId = loaded.descriptor.sourceId
            val pageSize = if (estimatedPageSize > 0) estimatedPageSize else PAGE_SIZE
            val page = when {
                offset == 0 -> {
                    lastPage = 1
                    lastOffset = 0
                    1
                }
                else -> (offset / pageSize) + 1
            }
            val query = filter?.query.orEmpty()

            val result = SourceGate.execute(effectiveSourceId) {
                when {
                    query.isNotBlank() -> loaded.source.getSearchManga(page, query, FilterList())
                    order == SortOrder.NEWEST || order == SortOrder.UPDATED || order == SortOrder.NEWEST_ASC || order == SortOrder.UPDATED_ASC ->
                        loaded.source.getLatestUpdates(page)
                    else -> loaded.source.getPopularManga(page)
                }
            }

            if (result.mangas.isNotEmpty()) {
                estimatedPageSize = result.mangas.size
                lastPage = page
                lastOffset = offset
            }

            val allFetched = ArrayList(result.mangas)
            // Ensure first page loads enough items to fill modern vertical screens
            if (offset == 0 && allFetched.size in 1..15 && result.hasNextPage) {
                runCatching {
                    val page2 = SourceGate.execute(effectiveSourceId) {
                        when {
                            query.isNotBlank() -> loaded.source.getSearchManga(2, query, FilterList())
                            order == SortOrder.NEWEST || order == SortOrder.UPDATED || order == SortOrder.NEWEST_ASC || order == SortOrder.UPDATED_ASC ->
                                loaded.source.getLatestUpdates(2)
                            else -> loaded.source.getPopularManga(2)
                        }
                    }
                    allFetched.addAll(page2.mangas)
                    lastPage = 2
                }
            }

            val mangas = allFetched.map { it.toKotatsuManga(emptyList()) }
            when (order) {
                SortOrder.ALPHABETICAL -> mangas.sortedBy { it.title.lowercase() }
                SortOrder.ALPHABETICAL_DESC -> mangas.sortedByDescending { it.title.lowercase() }
                else -> mangas
            }
        }

    override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
        val loaded = MihonSourceManager.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId
        val httpSource = loaded.source as? eu.kanade.tachiyomi.source.online.HttpSource

        val update = SourceGate.execute(effectiveSourceId) {
            loaded.source.getMangaUpdate(
                manga.toSManga(),
                manga.chapters.orEmpty().map { it.toSChapter() },
                fetchDetails = true,
                fetchChapters = httpSource?.let { !OlympusStaffParser.applies(it) } ?: true,
            )
        }

        val chapters = if (httpSource != null && OlympusStaffParser.applies(httpSource)) {
            val mangaUrl = manga.url.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: runCatching { httpSource.getMangaUrl(manga.toSManga()) }.getOrNull()
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: resolveUrl(httpSource.baseUrl, manga.url)

            SourceGate.execute(effectiveSourceId) {
                OlympusStaffParser.chapters(httpSource, mangaUrl).map { it.toKotatsuChapter() }
            }
        } else {
            update.chapters.orEmpty().map { it.toKotatsuChapter() }
        }

        update.manga.toKotatsuManga(chapters).copy(id = manga.id, source = loaded.descriptor)
    }

    override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val loaded = MihonSourceManager.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId
        val httpSource = loaded.source as? eu.kanade.tachiyomi.source.online.HttpSource

        val mihonPages = SourceGate.execute(effectiveSourceId) {
            if (httpSource != null && OlympusStaffParser.applies(httpSource)) {
                val chapterUrl = chapter.url.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: runCatching { httpSource.getChapterUrl(chapter.toSChapter()) }.getOrNull()
                        ?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: resolveUrl(httpSource.baseUrl, chapter.url)
                OlympusStaffParser.pages(httpSource, chapterUrl)
            } else {
                loaded.source.getPageList(chapter.toSChapter())
            }
        }

        mihonPages.map { page ->
            MangaPage(
                id = stableId(loaded.descriptor.name, "page:${page.index}:${page.url}"),
                url = page.url,
                preview = page.imageUrl.orEmpty(),
                source = loaded.descriptor,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
        val loaded = MihonSourceManager.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId
        val httpSource = loaded.source as? eu.kanade.tachiyomi.source.online.HttpSource
            ?: return@withContext page.url

        val mihonPage = Page(
            index = 0,
            url = page.url,
            imageUrl = (page.preview ?: "").takeIf { it.isNotBlank() },
        )

        SourceGate.execute(effectiveSourceId) {
            val imageUrl = runCatching { httpSource.getImageUrl(mihonPage) }.getOrNull() ?: page.preview
            (imageUrl ?: "").ifBlank { page.url }
        }
    }

    override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { java.net.URI(baseUrl.trimEnd('/') + "/").resolve(value).toString() }
            .getOrDefault(value)

    private fun SManga.toKotatsuManga(chapters: List<MangaChapter>): Manga = Manga(
        id = stableId(source.name, if (url.isNotBlank()) url else "${title}_$thumbnail_url"),
        title = title.ifBlank { "Untitled" },
        altTitles = emptySet(),
        url = url,
        publicUrl = url,
        rating = 0f,
        contentRating = if (source.isNsfw) ContentRating.ADULT else ContentRating.SAFE,
        coverUrl = thumbnail_url?.takeIf { it.isNotBlank() },
        tags = genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map {
            org.koitharu.kotatsu.parsers.model.MangaTag(title = it, key = it.lowercase(), source = source)
        }?.toSet().orEmpty(),
        state = status.toMangaState(),
        authors = listOfNotNull(author ?: artist).toSet(),
        largeCoverUrl = thumbnail_url?.takeIf { it.isNotBlank() },
        description = description,
        chapters = chapters,
        source = source,
    )

    private fun SMangaUpdate.toKotatsuManga(previous: Manga): Manga {
        val updatedChapters = chapters.orEmpty().map { it.toKotatsuChapter() }
        val updated = manga.toKotatsuManga(updatedChapters)
        return updated.copy(
            id = previous.id,
            source = source,
            description = updated.description.takeUnless { it.isNullOrBlank() } ?: previous.description,
            tags = updated.tags.ifEmpty { previous.tags },
        )
    }

    private fun SChapter.toKotatsuChapter(): MangaChapter = MangaChapter(
        id = stableId(source.name, "chapter:$url"),
        title = name.trim().removePrefix("#").trim().ifBlank { "Chapter ${chapter_number.takeIf { it > 0 } ?: ""}" }.trim(),
        number = chapter_number,
        volume = 0,
        url = url,
        scanlator = scanlator,
        uploadDate = date_upload,
        branch = "",
        source = source,
    )

    private fun Manga.toSManga(): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = coverUrl ?: ""
        it.author = authors.firstOrNull()
        it.artist = authors.firstOrNull()
        it.description = description
        it.genre = tags.joinToString { tag -> tag.title }.takeIf { s -> s.isNotBlank() }
        it.status = SManga.ONGOING
        it.initialized = chapters.orEmpty().isNotEmpty()
    }

    private fun MangaChapter.toSChapter(): SChapter = SChapter.create().also {
        it.url = url
        it.name = title.orEmpty()
        it.chapter_number = number
        it.scanlator = scanlator.orEmpty()
        it.date_upload = uploadDate
    }

    private fun Int.toMangaState(): MangaState = when (this) {
        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
        SManga.CANCELLED -> MangaState.ABANDONED
        SManga.ON_HIATUS -> MangaState.PAUSED
        SManga.LICENSED -> MangaState.RESTRICTED
        else -> MangaState.ONGOING
    }

    private companion object {
        const val PAGE_SIZE = 20

        fun stableId(namespace: String, value: String): Long =
            ("$namespace|$value").hashCode().toLong() and Long.MAX_VALUE
    }
}
