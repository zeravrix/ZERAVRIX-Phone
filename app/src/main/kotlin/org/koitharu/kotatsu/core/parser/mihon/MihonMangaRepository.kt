package org.zeravrix.kotatsu.core.parser.mihon

import android.content.Context
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
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
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override var defaultSortOrder = SortOrder.NEWEST

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    @Volatile private var estimatedPageSize = 0
    @Volatile private var lastOffset = -1
    @Volatile private var lastPage = 1

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
        withContext(Dispatchers.IO) {
            val loaded = MihonSourceRuntime.getLoadedOrAwait(context, source)
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
            val effectiveOrder = order ?: defaultSortOrder
            val isLatest = effectiveOrder == SortOrder.NEWEST || effectiveOrder == SortOrder.UPDATED ||
                effectiveOrder == SortOrder.NEWEST_ASC || effectiveOrder == SortOrder.UPDATED_ASC
            val supportsLatest = runCatching { loaded.source.supportsLatest }.getOrDefault(false)
            val httpSource = loaded.source as? HttpSource

            val result = SourceGate.execute(effectiveSourceId) {
                when {
                    query.isNotBlank() -> loaded.source.getSearchManga(page, query, FilterList())
                    isLatest && supportsLatest -> {
                        try {
                            loaded.source.getLatestUpdates(page)
                        } catch (_: Throwable) {
                            try {
                                loaded.source.getPopularManga(page)
                            } catch (_: Throwable) {
                                loaded.source.getSearchManga(page, "", FilterList())
                            }
                        }
                    }
                    else -> {
                        try {
                            loaded.source.getPopularManga(page)
                        } catch (_: Throwable) {
                            if (supportsLatest) {
                                try {
                                    loaded.source.getLatestUpdates(page)
                                } catch (_: Throwable) {
                                    loaded.source.getSearchManga(page, "", FilterList())
                                }
                            } else {
                                loaded.source.getSearchManga(page, "", FilterList())
                            }
                        }
                    }
                }
            }

            if (result.mangas.isNotEmpty()) {
                estimatedPageSize = result.mangas.size
                lastPage = page
                lastOffset = offset
            }

            val allFetched = ArrayList(result.mangas)

            val mangas = allFetched.mapNotNull { manga ->
                runCatching { manga.toKotatsuManga(emptyList(), httpSource) }.getOrNull()
            }
            when (effectiveOrder) {
                SortOrder.ALPHABETICAL -> mangas.sortedBy { it.title.lowercase() }
                SortOrder.ALPHABETICAL_DESC -> mangas.sortedByDescending { it.title.lowercase() }
                else -> mangas
            }
        }

    override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
        val loaded = MihonSourceRuntime.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId
        val httpSource = loaded.source as? HttpSource

        val update = guarded("details") { SourceGate.execute(effectiveSourceId) {
            loaded.source.getMangaUpdate(
                manga.toSManga(),
                manga.chapters.orEmpty().map { it.toSChapter() },
                fetchDetails = true,
                fetchChapters = true,
            )
        } }

        val rawChapters = update.chapters.orEmpty().mapNotNull { runCatching { it.toKotatsuChapter(manga.url) }.getOrNull() }
        val chapters = normalizeChapterOrder(rawChapters)

        update.manga.toKotatsuManga(
            chapters = chapters,
            httpSource = httpSource,
            fallbackUrl = manga.url,
            fallbackTitle = manga.title,
            fallbackCover = manga.coverUrl,
        ).copy(id = manga.id, source = loaded.descriptor)
    }

    override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val loaded = MihonSourceRuntime.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId

        val sChapter = chapter.toSChapter()
        val mihonPages = try {
            guarded("pages") { SourceGate.execute(effectiveSourceId) {
                loaded.source.getPageList(sChapter)
            } }
        } catch (e: Exception) {
            val isRefreshNeeded = e.message?.contains("Refresh", ignoreCase = true) == true ||
                e.message?.contains("Chapter List", ignoreCase = true) == true
            if (isRefreshNeeded) {
                val parentMangaUrl = MihonMemoStore.getChapterMangaUrl(source.name, chapter.url)
                    ?: run {
                        val cleanUrl = chapter.url.substringBefore('#').trimEnd('/')
                        cleanUrl.substringBeforeLast('/').ifBlank { chapter.url }
                    }
                var freshChapter: SChapter? = null
                runCatching {
                    val update = SourceGate.execute(effectiveSourceId) {
                        loaded.source.getMangaUpdate(
                            SManga.create().apply {
                                url = parentMangaUrl
                                title = ""
                            },
                            emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        )
                    }
                    val found = update.chapters?.find { it.url == chapter.url || it.url == sChapter.url }
                    if (found != null) {
                        freshChapter = found
                    }
                    update.chapters?.forEach { ch ->
                        runCatching { ch.memo }.getOrNull()?.let { m ->
                            MihonMemoStore.putChapterMemo(source.name, ch.url, m)
                            MihonMemoStore.putChapterMangaUrl(source.name, ch.url, parentMangaUrl)
                        }
                    }
                }
                val chapterToLoad = freshChapter ?: chapter.toSChapter()
                guarded("pages") { SourceGate.execute(effectiveSourceId) {
                    loaded.source.getPageList(chapterToLoad)
                } }
            } else {
                throw e
            }
        }

        mihonPages.mapIndexed { i, page ->
            val pageUrl = page.url
            val imageUrl = page.imageUrl.orEmpty()
            MangaPage(
                id = stableId(loaded.descriptor.name, "page:$i:$pageUrl:$imageUrl"),
                url = pageUrl.ifBlank { imageUrl },
                preview = imageUrl,
                source = loaded.descriptor,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
        val preview = page.preview
        if (!preview.isNullOrBlank() && (preview.startsWith("http://") || preview.startsWith("https://"))) {
            return@withContext preview
        }

        val loaded = MihonSourceRuntime.getLoadedOrAwait(context, source)
        val effectiveSourceId = loaded.descriptor.sourceId
        val httpSource = loaded.source as? HttpSource
            ?: return@withContext page.url

        val mihonPage = Page(
            index = 0,
            url = page.url,
            imageUrl = preview?.takeIf { it.isNotBlank() },
        )

        SourceGate.execute(effectiveSourceId) {
            val imageUrl = runCatching { httpSource.getImageUrl(mihonPage) }.getOrNull() ?: preview
            (imageUrl ?: "").ifBlank { page.url }
        }
    }

    /**
     * Extensions may leave lateinit SManga/SChapter fields unset. Mihon surfaces that as an
     * error naming the source; do the same instead of showing a bare "lateinit property" text.
     */
    private inline fun <T> guarded(operation: String, block: () -> T): T = try {
        block()
    } catch (e: UninitializedPropertyAccessException) {
        throw IllegalStateException(
            "${source.name}: the extension returned incomplete data ($operation): ${e.message}. " +
                "Try updating the extension.",
            e,
        )
    }

    override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { java.net.URI(baseUrl.trimEnd('/') + "/").resolve(value).toString() }
            .getOrDefault(value)

    // Mihon extensions are allowed to leave SManga.url / SManga.title unset in detail responses
    // Mihon extensions are allowed to leave SManga.url / SManga.title unset in detail responses
    // (they are lateinit). Mihon itself guards against UninitializedPropertyAccessException.
    private fun SManga.toKotatsuManga(
        chapters: List<MangaChapter>,
        httpSource: HttpSource? = null,
        fallbackUrl: String = "",
        fallbackTitle: String = "",
        fallbackCover: String? = null,
    ): Manga {
        val url = runCatching { this.url }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackUrl
        val title = runCatching { this.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val thumbnail_url = runCatching { this.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackCover
        val author = runCatching { this.author }.getOrNull()
        val artist = runCatching { this.artist }.getOrNull()
        val description = runCatching { this.description }.getOrNull()
        val genre = runCatching { this.genre }.getOrNull()
        val status = runCatching { this.status }.getOrDefault(SManga.ONGOING)

        val resolvedCover = thumbnail_url?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else if (httpSource != null) resolveUrl(httpSource.baseUrl, raw)
            else raw
        }
        val resolvedUrl = url.let { raw ->
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else if (httpSource != null) resolveUrl(httpSource.baseUrl, raw)
            else raw
        }
        runCatching { this.memo }.getOrNull()?.let { m ->
            MihonMemoStore.putMangaMemo(source.name, url, m)
        }
        return Manga(
            id = stableId(source.name, if (url.isNotBlank()) url else "${title}_$thumbnail_url"),
            title = title.ifBlank { "Untitled" },
            altTitles = emptySet(),
            url = url,
            publicUrl = resolvedUrl,
            rating = 0f,
            contentRating = if (source.isNsfw) ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = resolvedCover,
            tags = genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map {
                org.koitharu.kotatsu.parsers.model.MangaTag(title = it, key = it.lowercase(), source = source)
            }?.toSet().orEmpty(),
            state = status.toMangaState(),
            authors = listOfNotNull(author ?: artist).toSet(),
            largeCoverUrl = resolvedCover,
            description = description,
            chapters = chapters,
            source = source,
        )
    }

    private fun normalizeChapterOrder(chapters: List<MangaChapter>): List<MangaChapter> {
        if (chapters.size <= 1) return chapters
        val numbered = chapters.filter { it.number > 0f }
        if (numbered.size >= 2) {
            val firstNum = numbered.first().number
            val lastNum = numbered.last().number
            if (firstNum > lastNum) {
                // Descending (newest first, standard extension contract) -> reverse to ascending (first to latest)
                return chapters.reversed()
            } else if (firstNum < lastNum) {
                // Already in ascending order
                return chapters
            }
        }
        val withDates = chapters.filter { it.uploadDate > 0L }
        if (withDates.size >= 2) {
            if (withDates.first().uploadDate > withDates.last().uploadDate) {
                return chapters.reversed()
            } else if (withDates.first().uploadDate < withDates.last().uploadDate) {
                return chapters
            }
        }
        // Standard extension specification: chapters are ordered newest first by default
        return chapters.reversed()
    }

    private fun SMangaUpdate.toKotatsuManga(previous: Manga, httpSource: HttpSource? = null): Manga {
        val rawChapters = chapters.orEmpty().mapNotNull { runCatching { it.toKotatsuChapter(previous.url) }.getOrNull() }
        val updatedChapters = normalizeChapterOrder(rawChapters)
        val updated = manga.toKotatsuManga(updatedChapters, httpSource, previous.url, previous.title, previous.coverUrl)
        return updated.copy(
            id = previous.id,
            source = source,
            description = updated.description.takeUnless { it.isNullOrBlank() } ?: previous.description,
            tags = updated.tags.ifEmpty { previous.tags },
        )
    }

    private fun SChapter.toKotatsuChapter(mangaUrl: String? = null): MangaChapter {
        runCatching { memo }.getOrNull()?.let { m ->
            MihonMemoStore.putChapterMemo(source.name, url, m)
        }
        if (!mangaUrl.isNullOrBlank()) {
            MihonMemoStore.putChapterMangaUrl(source.name, url, mangaUrl)
        }
        return MangaChapter(
            id = stableId(source.name, "chapter:$url"),
            title = runCatching { name }.getOrDefault("").trim().removePrefix("#").trim().ifBlank { "Chapter ${chapter_number.takeIf { it > 0 } ?: ""}" }.trim(),
            number = chapter_number,
            volume = 0,
            url = url,
            scanlator = scanlator,
            uploadDate = date_upload,
            branch = "",
            source = source,
        )
    }

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

        var memoObj = MihonMemoStore.getMangaMemo(source.name, url)
        if (memoObj == null || !memoObj.containsKey("id")) {
            val hashId = url.substringAfterLast("#", "").toIntOrNull()
            if (hashId != null) {
                val slug = url.substringBefore("#").trim('/').substringAfterLast('/')
                memoObj = buildJsonObject {
                    put("id", hashId)
                    if (slug.isNotEmpty()) put("slug", slug)
                }
                MihonMemoStore.putMangaMemo(source.name, url, memoObj)
            }
        }
        if (memoObj != null) {
            it.memo = memoObj
        }
    }

    private fun MangaChapter.toSChapter(): SChapter = SChapter.create().also {
        it.url = url
        it.name = title.orEmpty()
        it.chapter_number = number
        it.scanlator = scanlator.orEmpty()
        it.date_upload = uploadDate

        var memoObj = MihonMemoStore.getChapterMemo(source.name, url)
        if (memoObj == null || !memoObj.containsKey("id")) {
            val hashId = url.substringAfterLast("#", "").toIntOrNull()
            if (hashId != null) {
                val segments = url.substringBefore("#").trim('/').split('/')
                val seriesSlug = if (segments.size >= 2) segments[segments.size - 2] else ""
                val slug = if (segments.isNotEmpty()) segments.last() else ""
                memoObj = buildJsonObject {
                    put("id", hashId)
                    if (seriesSlug.isNotEmpty()) put("seriesSlug", seriesSlug)
                    if (slug.isNotEmpty()) put("slug", slug)
                }
                MihonMemoStore.putChapterMemo(source.name, url, memoObj)
            }
        }
        if (memoObj != null) {
            it.memo = memoObj
        }
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

internal object MihonMemoStore {
    private val mangaMemoCache = ConcurrentHashMap<String, JsonObject>()
    private val chapterMemoCache = ConcurrentHashMap<String, JsonObject>()
    private val chapterToMangaUrl = ConcurrentHashMap<String, String>()

    private val prefs by lazy {
        okhttp3.internal.platform.PlatformRegistry.applicationContext?.getSharedPreferences("zeravrix_mihon_memos", Context.MODE_PRIVATE)
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun putMangaMemo(source: String, url: String, memo: JsonObject?) {
        if (memo != null && memo.isNotEmpty()) {
            mangaMemoCache["$source:$url"] = memo
            runCatching {
                prefs?.edit()?.putString("mm:$source:$url", memo.toString())?.apply()
            }
        }
    }

    fun getMangaMemo(source: String, url: String): JsonObject? {
        mangaMemoCache["$source:$url"]?.let { return it }
        val saved = prefs?.getString("mm:$source:$url", null) ?: return null
        return runCatching { json.parseToJsonElement(saved) as? JsonObject }.getOrNull()?.also {
            mangaMemoCache["$source:$url"] = it
        }
    }

    fun putChapterMemo(source: String, url: String, memo: JsonObject?) {
        if (memo != null && memo.isNotEmpty()) {
            chapterMemoCache["$source:$url"] = memo
            runCatching {
                prefs?.edit()?.putString("cm:$source:$url", memo.toString())?.apply()
            }
        }
    }

    fun getChapterMemo(source: String, url: String): JsonObject? {
        chapterMemoCache["$source:$url"]?.let { return it }
        val saved = prefs?.getString("cm:$source:$url", null) ?: return null
        return runCatching { json.parseToJsonElement(saved) as? JsonObject }.getOrNull()?.also {
            chapterMemoCache["$source:$url"] = it
        }
    }

    fun putChapterMangaUrl(source: String, chapterUrl: String, mangaUrl: String) {
        if (mangaUrl.isNotBlank()) {
            chapterToMangaUrl["$source:$chapterUrl"] = mangaUrl
            runCatching {
                prefs?.edit()?.putString("c2m:$source:$chapterUrl", mangaUrl)?.apply()
            }
        }
    }

    fun getChapterMangaUrl(source: String, chapterUrl: String): String? {
        chapterToMangaUrl["$source:$chapterUrl"]?.let { return it }
        val saved = prefs?.getString("c2m:$source:$chapterUrl", null) ?: return null
        chapterToMangaUrl["$source:$chapterUrl"] = saved
        return saved
    }
}
