package com.xlythe.tachiyomi.extension.all.nhentai

import com.xlythe.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NHentaiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses current SvelteKit gallery metadata`() {
        val apiBody =
            """
            {
              "id": 643574,
              "media_id": "3900000",
              "title": {
                "english": "Full English title",
                "japanese": "Japanese title",
                "pretty": "Pretty title"
              },
              "upload_date": 1785000000,
              "num_favorites": 42,
              "pages": [
                {
                  "number": 1,
                  "path": "galleries/3900000/1.webp",
                  "thumbnail": "galleries/3900000/1t.webp",
                  "width": 1200,
                  "height": 1800
                },
                {
                  "number": 2,
                  "path": "/galleries/3900000/2.png",
                  "thumbnail": "/galleries/3900000/2t.png",
                  "width": 1200,
                  "height": 1800
                }
              ],
              "thumbnail": {
                "path": "/galleries/3900000/thumb.webp",
                "width": 250,
                "height": 350
              },
              "tags": [
                {"id": 1, "name": "Example Artist", "type": "artist", "count": 1},
                {"id": 2, "name": "Example Group", "type": "group", "count": 1},
                {"id": 3, "name": "Example Tag", "type": "tag", "count": 1}
              ]
            }
            """.trimIndent()
        val metadata =
            buildJsonObject {
                put("status", JsonPrimitive(200))
                put("body", JsonPrimitive(apiBody))
            }
        val document =
            Jsoup.parse(
                """
                <html>
                  <body>
                    <script type="application/json" data-sveltekit-fetched
                        data-url="/api/v2/galleries/643574?include=pages">
                      $metadata
                    </script>
                  </body>
                </html>
                """.trimIndent(),
            )

        val gallery = document.getHentaiData(json)

        assertEquals(643574, gallery.id)
        assertEquals("Pretty title", gallery.title.pretty)
        assertEquals("Example Group", getGroups(gallery))
        assertEquals("https://t1.nhentai.net/galleries/3900000/thumb.webp", gallery.thumbnailUrl())
        assertEquals(
            listOf(
                "https://i1.nhentai.net/galleries/3900000/1.webp",
                "https://i1.nhentai.net/galleries/3900000/2.png",
            ),
            gallery.pageImageUrls(),
        )
    }

    @Test
    fun `group is absent when gallery has no group tag`() {
        val gallery =
            Hentai(
                id = 1,
                media_id = "2",
                tags = listOf(Tag("Example Artist", "artist")),
                title = Title(pretty = "Title"),
                upload_date = 0,
                num_favorites = 0,
            )

        assertNull(getGroups(gallery))
    }

    @Test
    fun `thumbnail falls back when API omits image metadata`() {
        val gallery =
            Hentai(
                id = 1,
                media_id = "2",
                tags = emptyList(),
                title = Title(pretty = "Title"),
                upload_date = 0,
                num_favorites = 0,
            )

        assertEquals("https://t1.nhentai.net/galleries/2/1t.webp", gallery.thumbnailUrl())
    }

    @Test
    fun `builds independent positive and negative tags from whitespace`() {
        assertEquals(
            """-tag:"futanari" tag:"lolicon" tag:"english" -tag:"male-only"""",
            buildNHentaiTagQuery("-futanari lolicon english -male-only"),
        )
    }

    @Test
    fun `supports comma-separated and quoted multi-word tags`() {
        assertEquals(
            """tag:"big breasts" -tag:"male only" tag:"full color"""",
            buildNHentaiTagQuery("""big breasts, -male only, "full color""""),
        )
        assertEquals(
            """tag:"big breasts" -tag:"male only"""",
            buildNHentaiTagQuery(""""big breasts" -"male only""""),
        )
    }

    @Test
    fun `filtered pagination retains query language tags and sort`() {
        val first =
            buildNHentaiSearchUrl(
                baseUrl = "https://nhentai.net",
                page = 1,
                query = "artist",
                language = "english",
                tags = "translated -full-color",
                sort = "popular-week",
            )
        val next =
            buildNHentaiSearchUrl(
                baseUrl = "https://nhentai.net",
                page = 2,
                query = "artist",
                language = "english",
                tags = "translated -full-color",
                sort = "popular-week",
            )

        val expectedQuery = """artist language:english tag:"translated" -tag:"full-color""""
        assertEquals(expectedQuery, first.queryParameter("q"))
        assertEquals(expectedQuery, next.queryParameter("q"))
        assertEquals("popular-week", first.queryParameter("sort"))
        assertEquals("popular-week", next.queryParameter("sort"))
        assertEquals("1", first.queryParameter("page"))
        assertEquals("2", next.queryParameter("page"))
    }

    @Test
    fun `finds next page in current nested pagination layout`() {
        val document =
            Jsoup.parse(
                """
                <div id="content">
                  <div class="container">
                    <div class="gallery"></div>
                    <section class="pagination desktop-pagination">
                      <a href="/search?q=tag&amp;page=1" class="page current">1</a>
                      <a href="/search?q=tag&amp;page=2" class="next" aria-label="Next page">Next</a>
                    </section>
                  </div>
                </div>
                """.trimIndent(),
            )

        assertNotNull(document.selectFirst(NHENTAI_NEXT_PAGE_SELECTOR))
    }
}
