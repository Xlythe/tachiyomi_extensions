package com.xlythe.tachiyomi.extension.en.mangabat

import keiyoushi.utils.scanLeadingDuplicates
import keiyoushi.utils.scanTrailingDuplicates
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangabatTest {
    @Test
    fun `chapter api response parses the new lazy-loaded chapter list`() {
        val response =
            Json { ignoreUnknownKeys = true }.decodeFromString<MangabatChapterResponse>(
                """
                {
                  "success": true,
                  "data": {
                    "chapters": [
                      {
                        "chapter_name": "Chapter 41",
                        "chapter_slug": "chapter-41",
                        "chapter_num": 41,
                        "updated_at": "2026-07-25T18:52:40.000000Z",
                        "view": 436
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(1, response.data.chapters.size)
        assertEquals(
            "Chapter 41",
            response.data.chapters
                .single()
                .name,
        )
        assertEquals(
            "chapter-41",
            response.data.chapters
                .single()
                .slug,
        )
    }

    @Test
    fun `leading page scan always checks four and stops when the window is mixed`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, false, true, true, true, true)

        val result =
            scanLeadingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(0, 1, 2, 3), inspected)
        assertEquals(setOf(0, 2, 3), result.duplicateIndices)
    }

    @Test
    fun `leading page scan continues until the first original page`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, true, true, true, true, false, true)

        val result =
            scanLeadingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(0, 1, 2, 3, 4, 5), inspected)
        assertEquals(setOf(0, 1, 2, 3, 4), result.duplicateIndices)
    }

    @Test
    fun `trailing page scan continues until the first original page`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, false, true, true, true, true, true)

        val result =
            scanTrailingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(6, 5, 4, 3, 2, 1), inspected)
        assertEquals(setOf(2, 3, 4, 5, 6), result.duplicateIndices)
    }

    @Test
    fun `Mangabat chapter urls expose series and chapter slugs`() {
        val location =
            "https://www.mangabats.com/manga/girls-baduk/chapter-29"
                .toMangabatChapterLocation()

        assertEquals("girls-baduk", location?.seriesSlug)
        assertEquals("chapter-29", location?.chapterSlug)
    }

    @Test
    fun `Mangabat chapter api selects an adjacent chapter at either edge`() {
        val chapters =
            listOf(
                MangabatChapter("Chapter 29", "chapter-29", ""),
                MangabatChapter("Chapter 28", "chapter-28", ""),
                MangabatChapter("Chapter 27", "chapter-27", ""),
            )

        assertEquals("chapter-28", chapters.adjacentChapterSlug("chapter-29"))
        assertEquals("chapter-28", chapters.adjacentChapterSlug("chapter-27"))
    }

    @Test
    fun `Mangabat image urls are scoped by series and chapter`() {
        val identity =
            "https://img-r1.2xstorage.com/girl-s-baduk/41/0.webp"
                .toMangabatPageIdentity()

        assertEquals("girl-s-baduk", identity?.seriesKey)
        assertEquals("41", identity?.chapterKey)
    }

    @Test
    fun `shared list selector excludes injected advertisements`() {
        val document =
            Jsoup.parse(
                """
                <div class="comic-list">
                  <div class="list-comic-item-wrap banner-class" hidden>
                    <a class="list-story-item js-banner-ai-list-link"
                        href="https://advertiser.example/" rel="nofollow">
                      <img class="js-banner-ai-list-img">
                    </a>
                  </div>
                  <div class="list-comic-item-wrap">
                    <a data-id="63392" class="list-story-item bookmark_check cover"
                        href="https://www.mangabats.com/manga/example-title"
                        title="Example title">
                      <img src="https://cdn.example/example.webp" alt="Example title">
                    </a>
                    <h3>
                      <a data-id="63392"
                          href="https://www.mangabats.com/manga/example-title">
                        Example title
                      </a>
                    </h3>
                  </div>
                </div>
                """.trimIndent(),
            )

        val results = document.select(Mangabat.MANGA_LIST_SELECTOR)

        assertEquals(1, results.size)
        assertTrue(results.single()!!.selectFirst("a[data-id]")!!.hasAttr("data-id"))
        assertFalse(results.single()!!.hasClass("banner-class"))
    }

    @Test
    fun `popular feed overrides the unfiltered multisource selector`() {
        assertTrue(
            Mangabat::class.java.declaredMethods.any {
                it.name == "popularMangaSelector" && it.parameterCount == 0
            },
        )
    }
}
