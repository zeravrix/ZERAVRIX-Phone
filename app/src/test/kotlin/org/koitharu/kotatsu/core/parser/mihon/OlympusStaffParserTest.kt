package org.zeravrix.kotatsu.core.parser.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlympusStaffParserTest {

    @Test
    fun `parses current chapter selector and orders chapters`() {
        val html = """
            <select id="select_chapter">
              <option value="/series/demo/1">فصل رقم : 1</option>
              <option value="https://olympustaff.com/series/demo/5">فصل رقم : 5</option>
              <option value="/series/demo/3">فصل رقم : 3</option>
              <option value="#">اختيار الفصل</option>
            </select>
        """.trimIndent()

        val chapters = OlympusStaffParser.parseChapters(html, "https://olympustaff.com/series/demo")

        assertEquals(3, chapters.size)
        assertEquals(5f, chapters[0].chapter_number)
        assertEquals("https://olympustaff.com/series/demo/1", chapters[2].url)
    }

    @Test
    fun `parses live chapter cards and Arabic chapter numbers`() {
        val html = """
            <div class="enhanced-chapters-grid" id="chaptersContainer">
              <a class="chapter-link" href="/series/demo/5">
                <div class="chapter-info">
                  <div class="chapter-number">الفصل 5</div>
                  <div class="chapter-title">الهروب نحو روندل.</div>
                </div>
              </a>
              <a class="chapter-link" href="https://olympustaff.com/series/demo/4">
                <div class="chapter-info">
                  <div class="chapter-number">الفصل 4</div>
                  <div class="chapter-title">البقاء على قيد الحياة.</div>
                </div>
              </a>
            </div>
        """.trimIndent()

        val chapters = OlympusStaffParser.parseChapters(html, "https://olympustaff.com/series/demo")

        assertEquals(2, chapters.size)
        assertEquals(5f, chapters[0].chapter_number)
        assertEquals("الفصل 5 الهروب نحو روندل.", chapters[0].name)
        assertEquals("https://olympustaff.com/series/demo/4", chapters[1].url)
    }

    @Test
    fun `parses saved current OlympusStaff series page`() {
        val html = checkNotNull(javaClass.getResourceAsStream("/org/koitharu/kotatsu/core/parser/mihon/olympustaff-series-current.html"))
            .bufferedReader()
            .use { it.readText() }

        val chapters = OlympusStaffParser.parseChapters(
            html,
            "https://olympustaff.com/series/surviving-in-another-world",
        )

        assertTrue(chapters.size >= 5)
        assertEquals(5f, chapters.first().chapter_number)
        assertTrue(chapters.first().url.endsWith("/surviving-in-another-world/5"))
    }

    @Test
    fun `parses current OlympusStaff reader image list`() {
        val html = checkNotNull(javaClass.getResourceAsStream("/org/koitharu/kotatsu/core/parser/mihon/olympustaff-chapter-5-current.html"))
            .bufferedReader()
            .use { it.readText() }

        val pages = OlympusStaffParser.parsePages(
            html,
            "https://olympustaff.com/series/surviving-in-another-world/5",
        )

        assertEquals(20, pages.size)
        assertTrue(pages.all { it.imageUrl?.contains("uploads/manga_955a1/5/") == true })
    }

    @Test
    fun `parses lazy and direct reader images`() {
        val html = """
            <div class="reader">
              <img src="data:image/gif;base64,x" data-src="/uploads/manga/demo/5/one.webp">
              <img data-lazy-src="https://olympustaff.com/uploads/manga/demo/5/two.jpg">
              <img src="/uploads/manga/demo/5/one.webp">
            </div>
        """.trimIndent()

        val pages = OlympusStaffParser.parsePages(html, "https://olympustaff.com/series/demo/5")

        assertEquals(2, pages.size)
        assertTrue(pages.any { it.imageUrl == "https://olympustaff.com/uploads/manga/demo/5/one.webp" })
        assertTrue(pages.any { it.imageUrl == "https://olympustaff.com/uploads/manga/demo/5/two.jpg" })
    }
}
