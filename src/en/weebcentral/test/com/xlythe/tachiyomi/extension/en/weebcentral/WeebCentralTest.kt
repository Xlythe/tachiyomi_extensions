package com.xlythe.tachiyomi.extension.en.weebcentral

import keiyoushi.utils.PageFingerprint
import keiyoushi.utils.hasDuplicateFingerprintAtEdge
import keiyoushi.utils.initialEdgeFingerprintPositions
import keiyoushi.utils.pageIndexForFingerprintPosition
import keiyoushi.utils.scanLeadingDuplicates
import keiyoushi.utils.scanTrailingDuplicates
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeebCentralTest {
    @Test
    fun `leading page scan always checks four and stops when the window is mixed`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, true, false, true, true, true)

        val result =
            scanLeadingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(0, 1, 2, 3), inspected)
        assertEquals(setOf(0, 1, 3), result.duplicateIndices)
    }

    @Test
    fun `leading page scan continues one at a time after four duplicates`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, true, true, true, true, true, false, true)

        val result =
            scanLeadingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), inspected)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), result.duplicateIndices)
    }

    @Test
    fun `trailing page scan checks four from the end and stops when mixed`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(false, false, false, false, false, true, true, true)

        val result =
            scanTrailingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(7, 6, 5, 4), inspected)
        assertEquals(setOf(7, 6, 5), result.duplicateIndices)
    }

    @Test
    fun `trailing page scan continues backward after four duplicates`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, true, false, true, true, true, true, true)

        val result =
            scanTrailingDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals(listOf(7, 6, 5, 4, 3, 2), inspected)
        assertEquals(setOf(7, 6, 5, 4, 3), result.duplicateIndices)
    }

    @Test
    fun `initial bootstrap positions cover both chapter edges`() {
        assertEquals(
            listOf(0, 1, 2, 3, -1, -2, -3, -4),
            initialEdgeFingerprintPositions(pageCount = 10),
        )
        assertEquals(9, pageIndexForFingerprintPosition(position = -1, pageCount = 10))
        assertEquals(6, pageIndexForFingerprintPosition(position = -4, pageCount = 10))
    }

    @Test
    fun `shifted trailing ads match anywhere within the trailing edge`() {
        val exactAd = PageFingerprint("ad-a", 0x12, 1200, 800)
        val shiftedSubtleAd = PageFingerprint("ad-b", 0x13, 1200, 800)
        val references =
            mapOf(
                0 to listOf(exactAd),
                -4 to listOf(exactAd),
                -3 to listOf(shiftedSubtleAd),
            )

        assertTrue(references.hasDuplicateFingerprintAtEdge(position = -1, candidate = exactAd))
        assertFalse(references.hasDuplicateFingerprintAtEdge(position = -1, candidate = shiftedSubtleAd.copy(sha256 = "changed")))
        assertTrue(references.hasDuplicateFingerprintAtEdge(position = -3, candidate = shiftedSubtleAd.copy(sha256 = "changed")))
        assertFalse(references.hasDuplicateFingerprintAtEdge(position = 1, candidate = shiftedSubtleAd))
    }

    @Test
    fun `supplied Time Stop Hero chapter removes its three shifted ending ads`() {
        fun fingerprint(sha256: String) = PageFingerprint(sha256, null, 0, 0)

        val references =
            mapOf(
                -6 to listOf(fingerprint("216143")),
                -5 to listOf(fingerprint("D73FF1")),
                -4 to listOf(fingerprint("A61D20")),
                -3 to listOf(fingerprint("EB3A6F")),
                -2 to listOf(fingerprint("A5B0A3")),
                -1 to listOf(fingerprint("F90762")),
            )
        val current =
            listOf("290CF0", "41A4D3", "FFF835", "A61D20", "EB3A6F", "F90762")
                .map(::fingerprint)

        val result =
            scanTrailingDuplicates(current) { index, candidate ->
                references.hasDuplicateFingerprintAtEdge(
                    position = index - current.size,
                    candidate = candidate,
                )
            }

        assertEquals(setOf(3, 4, 5), result.duplicateIndices)
    }

    @Test
    fun `chapter controller yields a real adjacent chapter`() {
        val controller =
            """
            window.location.href = "https://weebcentral.com/chapters/01KYHSWP60620GS9NAMMANGK8C?is_prev=True"
            window.location.href = "https://weebcentral.com/chapters/None"
            """.trimIndent()

        assertEquals(
            "https://weebcentral.com/chapters/01KYHSWP60620GS9NAMMANGK8C?is_prev=True",
            controller.findWeebCentralAdjacentChapterUrl("01KYHZR86GFZ8J7J3C9M7CN5B4"),
        )
    }

    @Test
    fun `perceptual fingerprints accept subtle changes but reject different pages`() {
        val reference = PageFingerprint("sha-a", 0x73f2cf9e38bf7eb3, 1200, 800)
        val subtleChange = PageFingerprint("sha-b", 0x73f2cf9e38bf5e93, 1200, 800)
        val differentPage = PageFingerprint("sha-c", 0x0f0f0f0f0f0f0f0f, 1200, 800)

        assertTrue(reference.isDuplicateOf(subtleChange))
        assertFalse(reference.isDuplicateOf(differentPage))
    }

    @Test
    fun `WeebCentral image urls are scoped by series and chapter`() {
        val identity =
            "https://scans.lastation.us/manga/the-extras-academy-survival-guide/0116-001.png"
                .toWeebCentralPageIdentity()

        assertEquals("the-extras-academy-survival-guide", identity?.seriesKey)
        assertEquals("0116", identity?.chapterKey)
    }

    @Test
    fun `listing parser strips Official prefix without dropping the manga`() {
        val document =
            Jsoup.parse(
                """
                <article><section>
                  <a href="/series/official"><img src="official.jpg"><div>Official Hidden Work</div></a>
                  <a href="/series/normal"><img src="normal.jpg"><div>Visible Work</div></a>
                  <a href="/series/middle"><img src="middle.jpg"><div>The Official Guide</div></a>
                  <a href="/series/officially"><img src="officially.jpg"><div>Officially Yours</div></a>
                </section></article>
                <button>Next</button>
                """.trimIndent(),
                "https://weebcentral.com",
            )

        val titles =
            document
                .selectWeebCentralMangaElements("article > section > a")
                .map { it.weebCentralTitle(WEEBCENTRAL_LIST_TITLE_SELECTOR) }

        assertEquals(
            listOf("Hidden Work", "Visible Work", "The Official Guide", "Officially Yours"),
            titles,
        )
        assertTrue(document.selectFirst("button") != null)
    }

    @Test
    fun `Official prefix stripping handles casing and web whitespace`() {
        assertEquals("Work", "Official Work".withoutWeebCentralOfficialPrefix())
        assertEquals("Work", "  OFFICIAL   Work".withoutWeebCentralOfficialPrefix())
        assertEquals("Work", "\u200BOfficial\u00A0Work".withoutWeebCentralOfficialPrefix())
        assertEquals("Work", "official\u202FWork".withoutWeebCentralOfficialPrefix())
        assertEquals("Work", "\uFEFFOfficial\u2060Work".withoutWeebCentralOfficialPrefix())

        assertEquals("Officially Yours", "Officially Yours".withoutWeebCentralOfficialPrefix())
        assertEquals("The Official Work", "The Official Work".withoutWeebCentralOfficialPrefix())
        assertEquals("Official", "Official".withoutWeebCentralOfficialPrefix())
    }

    @Test
    fun `detail title parser strips Official from Sakamoto Days`() {
        val detail = Jsoup.parse("<section><h1>Official Sakamoto Days</h1></section>")

        assertEquals("Sakamoto Days", detail.weebCentralTitle("h1"))
    }
}
