package org.zeravrix.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.jsoup.Jsoup

/** Compatibility parser for the current OlympusStaff HTML layout used by Team X Novel. */
object OlympusStaffParser {

    fun applies(source: HttpSource): Boolean =
        source.baseUrl.contains("olympustaff.com", ignoreCase = true)

    suspend fun chapters(source: HttpSource, mangaUrl: String): List<SChapter> =
        parseChapters(requestDocument(source, mangaUrl).outerHtml(), mangaUrl)

    fun parseChapters(html: String, baseUrl: String): List<SChapter> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("select#select_chapter option, a.chapter-link[href]")
            .mapNotNull { element ->
                val isOption = element.tagName().equals("option", ignoreCase = true)
                val rawUrl = element.attr(if (isOption) "value" else "href").trim()
                if (rawUrl.isBlank() || rawUrl.startsWith("#")) return@mapNotNull null
                val attribute = if (isOption) "value" else "href"
                val url = element.absUrl(attribute).ifBlank {
                    runCatching { java.net.URI(baseUrl).resolve(rawUrl).toString() }.getOrDefault(rawUrl)
                }
                if (url.isBlank()) return@mapNotNull null
                val title = if (isOption) {
                    element.text().trim()
                } else {
                    listOfNotNull(
                        element.selectFirst(".chapter-number")?.text()?.trim(),
                        element.selectFirst(".chapter-title")?.text()?.trim(),
                    ).joinToString(" ").ifBlank { element.text().trim() }
                }.ifBlank { "Chapter" }
                val number = chapterNumber(title) ?: chapterNumber(url) ?: 0f
                SChapter.create().also {
                    it.url = url
                    it.name = title
                    it.chapter_number = number
                    it.date_upload = 0L
                    it.scanlator = ""
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    suspend fun pages(source: HttpSource, chapterUrl: String): List<Page> =
        parsePages(requestDocument(source, chapterUrl).outerHtml(), chapterUrl)

    fun parsePages(html: String, baseUrl: String): List<Page> {
        val document = Jsoup.parse(html, baseUrl)
        val images = document.select("div.image_list img[src], div.image_list img[data-src], div.image_list img[data-lazy-src]")
            .ifEmpty { document.select("img[src], img[data-src], img[data-lazy-src]") }
        return images
            .mapNotNull { image ->
                val candidate = sequenceOf(
                    "data-src" to image.attr("data-src"),
                    "data-lazy-src" to image.attr("data-lazy-src"),
                    "src" to image.attr("src"),
                ).firstOrNull { (attribute, value) -> value.isNotBlank() && !value.startsWith("data:") }
                    ?: return@mapNotNull null
                val url = image.absUrl(candidate.first).ifBlank {
                    runCatching { java.net.URI(baseUrl).resolve(candidate.second).toString() }
                        .getOrDefault(candidate.second)
                }
                if (!url.startsWith("http", ignoreCase = true)) return@mapNotNull null
                Page(index = 0, url = url, imageUrl = url)
            }
            .distinctBy { it.imageUrl ?: it.url }
            .mapIndexed { index, page -> Page(index, page.url, page.imageUrl) }
    }

    private suspend fun requestDocument(source: HttpSource, url: String) =
        source.client.newCall(
            Request.Builder()
                .url(url)
                .headers(source.headers)
                .get()
                .build(),
        ).execute().use { response ->
            check(response.isSuccessful) { "OlympusStaff request failed: ${response.code}" }
            Jsoup.parse(response.body?.string().orEmpty(), url)
        }


    private fun chapterNumber(value: String): Float? =
        TITLE_CHAPTER_NUMBER.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: URL_CHAPTER_NUMBER.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    private val TITLE_CHAPTER_NUMBER = Regex(
        "(?:chapter|episode|الفصل|فصل)\\s*(?:رقم\\s*)?[:：#-]?\\s*(\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val URL_CHAPTER_NUMBER = Regex("/(\\d+(?:\\.\\d+)?)/?$")
}
