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
    fun `normalized full page signatures tolerate resizing but reject comic pages`() {
        val donationHash = ByteArray(256) { (it % 2).toByte() }
        val resizedDonationHash = donationHash.copyOf().apply {
            repeat(32) { index -> this[index] = (1 - this[index]).toByte() }
        }
        val unrelatedHash = donationHash.copyOf().apply {
            repeat(33) { index -> this[index] = (1 - this[index]).toByte() }
        }
        val donation = MangabatFullPageSignature(720, 1370, donationHash)

        assertTrue(donation.isDuplicateOf(MangabatFullPageSignature(720, 1500, resizedDonationHash)))
        assertFalse(donation.isDuplicateOf(MangabatFullPageSignature(720, 1500, unrelatedHash)))
        assertFalse(donation.isDuplicateOf(MangabatFullPageSignature(720, 1800, donationHash)))
        assertFalse(donation.isDuplicateOf(MangabatFullPageSignature(800, 1370, donationHash)))
    }

    @Test
    fun `shifted scaled title cards match while ordinary pages fail closed`() {
        val titleCard = keiyoushi.utils.PageFingerprint("current", 0b0011, 720, 1370)
        val scaledTitleCard = keiyoushi.utils.PageFingerprint("adjacent", 0b0000, 720, 1500)
        val unrelatedPage = keiyoushi.utils.PageFingerprint("unrelated", -1L, 720, 1500)
        val excessivelyDifferentCanvas = keiyoushi.utils.PageFingerprint("tall", 0b0000, 720, 1800)

        assertTrue(titleCard.isShiftedMangabatOpeningDuplicateOf(scaledTitleCard))
        assertFalse(titleCard.isShiftedMangabatOpeningDuplicateOf(unrelatedPage))
        assertFalse(titleCard.isShiftedMangabatOpeningDuplicateOf(excessivelyDifferentCanvas))
    }

    @Test
    fun `shifted title cards only compare against opening fingerprints`() {
        val titleCard = keiyoushi.utils.PageFingerprint("current", 0b0011, 720, 1370)
        val reference = keiyoushi.utils.PageFingerprint("adjacent", 0b0000, 720, 1500)

        assertTrue(mapOf(5 to listOf(reference)).hasShiftedMangabatOpeningDuplicate(titleCard))
        assertFalse(mapOf(-1 to listOf(reference)).hasShiftedMangabatOpeningDuplicate(titleCard))
    }

    @Test
    fun `mixed opening content still inspects delayed spam cards`() {
        val inspected = mutableListOf<Int>()
        val duplicateStates = listOf(true, false, false, true, false, false, false, true, true, false, false, false, false)

        val result =
            scanMangabatEarlyDuplicates(duplicateStates) { index, duplicate ->
                inspected += index
                duplicate
            }

        assertEquals((0..11).toList(), inspected)
        assertTrue(8 in inspected)
        assertEquals(setOf(0, 3, 7, 8), result.duplicateIndices)
    }

    @Test
    fun `adjacent chapter fingerprints cover twelve opening pages and four ending pages`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, -1, -2, -3, -4),
            mangabatFingerprintPositions(pageCount = 20),
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

    @Test
    fun `known full page advertisements are removed only on an exact content hash`() {
        val donationNotice =
            "380976af8896613cf41c8baf933150f1566608bbce7d2512de1016e81c58a735"
                .toMangabatEdgePageEdit()
        val donationRequest =
            "3dad81b91bab96c116b000d1858584f1ff97f217c89ec4418f9c62e02f9cfbb4"
                .toMangabatEdgePageEdit()
        val donationContinuationOne =
            "440aa8e557171633e95dfc62be2c599dc08523a5a60d35b0d45ee0362ce3f213"
                .toMangabatEdgePageEdit()
        val donationContinuationTwo =
            "f5646528dd39539bf2eeff1012f5595cf6ab2fed9aa31575e0f3e9ef18f95889"
                .toMangabatEdgePageEdit()
        val donationExplainer =
            "289b09d0300c8233b51b3f34960293cfe306b64aa15621e0485f52c31108e638"
                .toMangabatEdgePageEdit()
        val openingAd =
            "613b33ef64a03537477d080305788367b14468d6bbbc2fb58e8740f4ce84d6ff"
                .toMangabatEdgePageEdit()
        val endingAd =
            "58593fee88b74a204572f1607791f92cc4cc7e64843c58c1fa7237ef7ad71b62"
                .toMangabatEdgePageEdit()
        val demonicScansEndingAd =
            "28bac9126d70960bdb5ba62e6408f8862df470424b2cd483abff2fd5c7d600e7"
                .toMangabatEdgePageEdit()

        assertEquals(true, donationNotice?.remove)
        assertEquals(true, donationRequest?.remove)
        assertEquals(true, donationContinuationOne?.remove)
        assertEquals(true, donationContinuationTwo?.remove)
        assertEquals(true, donationExplainer?.remove)
        assertEquals(true, openingAd?.remove)
        assertEquals(true, endingAd?.remove)
        assertEquals(true, demonicScansEndingAd?.remove)
        assertEquals(null, "changed-content".toMangabatEdgePageEdit())
    }

    @Test
    fun `mixed title card page preserves the real panel above it`() {
        val edit =
            "ce3d5b01101395f9732572ddc166ea514c2ac30486766e1d5afe994e99f6de7d"
                .toMangabatEdgePageEdit()

        assertEquals(
            MangabatCropBounds(x = 0, y = 0, width = 720, height = 715),
            edit?.cropBounds(imageWidth = 720, imageHeight = 1370),
        )
    }

    @Test
    fun `second half of a split title card preserves the dialogue below it`() {
        val edit =
            "485b505800f236d2021e0069b2906cc5c3cce0c3c94c8d4863aca09c8a05416b"
                .toMangabatEdgePageEdit()

        assertEquals(
            MangabatCropBounds(x = 0, y = 545, width = 720, height = 825),
            edit?.cropBounds(imageWidth = 720, imageHeight = 1370),
        )
    }

    @Test
    fun `mixed opening page keeps the first real comic panel`() {
        val edit =
            "0ddd9b6cd92872567fc4cf0a06aec285c29fe8386f4e7671221525cef60fadec"
                .toMangabatEdgePageEdit()

        assertEquals(
            MangabatCropBounds(x = 0, y = 1024, width = 720, height = 314),
            edit?.cropBounds(imageWidth = 720, imageHeight = 1338),
        )
    }

    @Test
    fun `mixed ending page keeps the final speech panel`() {
        val edit =
            "6039c39c95974e54c3eb850649b4c0fc6689f0865b054c42fbc7023f94a5ec47"
                .toMangabatEdgePageEdit()

        assertEquals(
            MangabatCropBounds(x = 0, y = 0, width = 720, height = 425),
            edit?.cropBounds(imageWidth = 720, imageHeight = 1321),
        )
    }

    @Test
    fun `standalone announcement page is removed by exact content hash`() {
        val edit =
            "96267e22823639ca84cf4b0a20f2a3836d122b56bfe76da93758dc84148c48ab"
                .toMangabatEdgePageEdit()

        assertEquals(true, edit?.remove)
    }

    @Test
    fun `stitched announcement preserves the real comic below it`() {
        val edit =
            "350e5adee50348d8265f093b2b89e8a85c9ecd975929999dcfa496387f53b674"
                .toMangabatEdgePageEdit()

        assertEquals(
            MangabatCropBounds(x = 0, y = 470, width = 800, height = 1030),
            edit?.cropBounds(imageWidth = 800, imageHeight = 1500),
        )
    }

    @Test
    fun `named series promo pages use exact safe edits`() {
        val removals =
            listOf(
                "a4773fe64cd1ad1755c917dc9b25989b4d5d37f26535240818a39797db41c12b",
                "f87af2f14ac21ebb326c0141ce113cd37f17f9a8875ec4832897efac09fb1f50",
            )
        removals.forEach { assertEquals(true, it.toMangabatEdgePageEdit()?.remove) }

        val crops =
            listOf(
                "063047489f33ffb0cf11bd57edccc3cdca9c2dfd6df7c77457755f0c74c43663" to MangabatCropBounds(0, 130, 720, 1370),
                "eff3515ade6737a44348a7c88027fda5b3fc2068c66d5e5e9393f2a14c523f93" to MangabatCropBounds(0, 0, 720, 940),
                "0a6f7ee3beca4d8c2de0cf196e1a9b694c19ce166dfbc836e2123d512f27f342" to MangabatCropBounds(0, 640, 720, 860),
                "a4ed10cb1dd8f175ffd6932d5e0d6a933b4efef2ced0f49b840c7133b131394e" to MangabatCropBounds(0, 480, 800, 1020),
                "3d2c87c8cc0c28a5ba5ce25e627cb6adcd5d99935ca479b512dc29489cfbd080" to MangabatCropBounds(0, 440, 800, 1004),
            )
        crops.forEach { (hash, expected) ->
            assertEquals(expected, hash.toMangabatEdgePageEdit()?.cropBounds(expected.width, expected.y + expected.height))
        }
    }

    @Test
    fun `stale crop coordinates fail closed`() {
        val edit = MangabatEdgePageEdit(topOffset = 1024, retainedHeight = 400)

        assertEquals(null, edit.cropBounds(imageWidth = 720, imageHeight = 1338))
    }

    @Test
    fun `cropped pages use a stable cache-distinct image url`() {
        val original = "https://example.org/series/chapter/0.webp?token=abc"
        val bounds = MangabatCropBounds(x = 0, y = 425, width = 800, height = 900)

        val transformed = original.withMangabatEdgeCropCacheKey(bounds)

        assertTrue(transformed.startsWith("https://example.org/series/chapter/0.webp?"))
        assertTrue(transformed.contains("token=abc"))
        assertTrue(transformed.contains("tachiyomi_edge_crop=v4-0-425-800-900"))
        assertEquals(transformed, original.withMangabatEdgeCropCacheKey(bounds))
        assertFalse(transformed == original)
    }

    @Test
    fun `edge inspection bypasses previously transformed image cache entries`() {
        val request = okhttp3.Request.Builder().url("https://example.org/page.webp").build()

        val freshRequest = request.withFreshMangabatImageInspection()

        assertTrue(freshRequest.cacheControl.noCache)
        assertEquals("v1", freshRequest.url.queryParameter("tachiyomi_edge_inspection"))
    }

    @Test
    fun `strong seam signatures crop matching blocks and reject unrelated blocks`() {
        val profile = edgeProfile(List(58) { 100 }, originalHeight = 1444)
        val candidateHash = ByteArray(256) { (it % 2).toByte() }
        val matchingHash = candidateHash.copyOf().apply {
            repeat(70) { index -> this[index] = (1 - this[index]).toByte() }
        }
        val unrelatedHash = candidateHash.copyOf().apply {
            repeat(73) { index -> this[index] = (1 - this[index]).toByte() }
        }
        val candidate = MangabatEdgeRegionSignature(edgeRows = 18, fromTop = true, hash = candidateHash)

        val edit =
            profile.generalizedSeamEdit(
                signatures = listOf(candidate),
                references =
                listOf(MangabatEdgeRegionSignature(edgeRows = 18, fromTop = true, hash = matchingHash)),
                inspectLeading = true,
                inspectTrailing = false,
            )
        val rejected =
            profile.generalizedSeamEdit(
                signatures = listOf(candidate),
                references =
                listOf(MangabatEdgeRegionSignature(edgeRows = 18, fromTop = true, hash = unrelatedHash)),
                inspectLeading = true,
                inspectTrailing = false,
            )

        assertEquals(
            MangabatCropBounds(x = 0, y = 450, width = 800, height = 994),
            edit?.cropBounds(imageWidth = 800, imageHeight = 1444),
        )
        assertEquals(null, rejected)
    }

    @Test
    fun `opposite edge seam matches cannot crop normal artwork`() {
        val profile = edgeProfile(List(58) { 100 }, originalHeight = 1444)
        val hash = ByteArray(256) { (it % 2).toByte() }
        val candidate = MangabatEdgeRegionSignature(edgeRows = 18, fromTop = true, hash = hash)
        val oppositeEdge = MangabatEdgeRegionSignature(edgeRows = 18, fromTop = false, hash = hash)

        assertEquals(
            null,
            profile.generalizedSeamEdit(
                signatures = listOf(candidate),
                references = listOf(oppositeEdge),
                inspectLeading = true,
                inspectTrailing = false,
            ),
        )
    }

    @Test
    fun `a single fuzzy detector cannot authorize a destructive crop`() {
        val seam = MangabatEdgePageEdit(topOffset = 450)
        val strip = MangabatEdgePageEdit(topOffset = 475)

        assertEquals(null, agreeingMangabatEdgeEdit(seam, null, 800, 1444))
        assertEquals(null, agreeingMangabatEdgeEdit(null, strip, 800, 1444))
        assertEquals(null, agreeingMangabatEdgeEdit(seam, strip, 800, 1444))
        assertEquals(seam, agreeingMangabatEdgeEdit(seam, seam, 800, 1444))
    }

    @Test
    fun `one pixel placeholders are removed without dropping thin real slices`() {
        assertTrue(isStructurallyInvalidMangabatEdgeImage(width = 800, height = 1))
        assertTrue(isStructurallyInvalidMangabatEdgeImage(width = 1, height = 1500))
        assertFalse(isStructurallyInvalidMangabatEdgeImage(width = 900, height = 25))
    }

    @Test
    fun `repeated leading strips are cropped without series hashes`() {
        val banner = listOf(20, 230, 35, 210, 50, 195, 65, 180, 80, 165)
        val candidate = edgeProfile(banner + List(30) { 96 + it })
        val reference = edgeProfile(banner + List(25) { 40 + it })

        val edit =
            candidate.generalizedEdgeEdit(
                references = listOf(reference),
                inspectLeading = true,
                inspectTrailing = false,
            )

        assertEquals(
            MangabatCropBounds(x = 0, y = 250, width = 800, height = 750),
            edit?.cropBounds(imageWidth = 800, imageHeight = 1000),
        )
    }

    @Test
    fun `interior artwork matches cannot crop a leading page`() {
        val artwork = listOf(20, 230, 35, 210, 50, 195, 65, 180, 80, 165)
        val candidate = edgeProfile(artwork + List(30) { 96 + it })
        val reference = edgeProfile(List(5) { 140 + it } + artwork + List(20) { 40 + it })

        assertEquals(
            null,
            candidate.generalizedEdgeEdit(
                references = listOf(reference),
                inspectLeading = true,
                inspectTrailing = false,
            ),
        )
    }

    @Test
    fun `partial strip matches expand to a nearby strong content boundary`() {
        val banner = listOf(20, 50, 80, 110, 140, 170, 200, 220, 220, 220, 220, 220)
        val candidate = edgeProfile(banner + List(28) { 20 })
        val partialReference = edgeProfile(banner.take(8) + List(20) { 20 })

        val edit =
            candidate.generalizedEdgeEdit(
                references = listOf(partialReference),
                inspectLeading = true,
                inspectTrailing = false,
            )

        assertEquals(
            MangabatCropBounds(x = 0, y = 300, width = 800, height = 700),
            edit?.cropBounds(imageWidth = 800, imageHeight = 1000),
        )
    }

    @Test
    fun `repeated trailing strips are cropped while retaining credits`() {
        val promotion = listOf(15, 240, 30, 225, 45, 210, 60, 195, 75, 180, 90, 165)
        val candidate = edgeProfile(List(24) { 80 + it } + promotion, originalHeight = 900)
        val reference = edgeProfile(List(4) { 245 + it } + promotion, originalHeight = 400)

        val edit =
            candidate.generalizedEdgeEdit(
                references = listOf(reference),
                inspectLeading = false,
                inspectTrailing = true,
            )

        assertEquals(
            MangabatCropBounds(x = 0, y = 0, width = 800, height = 600),
            edit?.cropBounds(imageWidth = 800, imageHeight = 900),
        )
    }

    @Test
    fun `blank margins and unsafe crops are left untouched`() {
        val blank = edgeProfile(List(20) { 255 }, originalHeight = 500)
        val matchingBlank = edgeProfile(List(20) { 255 }, originalHeight = 500)
        val oversizedBanner =
            edgeProfile(
                List(10) { if (it % 2 == 0) 10 else 240 } + List(6) { 100 + it },
                originalHeight = 400,
            )
        val bannerReference =
            edgeProfile(
                List(10) { if (it % 2 == 0) 10 else 240 },
                originalHeight = 250,
            )

        assertEquals(
            null,
            blank.generalizedEdgeEdit(listOf(matchingBlank), inspectLeading = true, inspectTrailing = false),
        )
        assertEquals(
            null,
            oversizedBanner.generalizedEdgeEdit(listOf(bannerReference), inspectLeading = true, inspectTrailing = false),
        )
    }

    private fun edgeProfile(
        rowValues: List<Int>,
        originalHeight: Int = 1000,
    ): MangabatEdgeStripProfile {
        val profileWidth = 32
        val luma =
            ByteArray(rowValues.size * profileWidth) { index ->
                rowValues[index / profileWidth].toByte()
            }
        return MangabatEdgeStripProfile(
            originalWidth = 800,
            originalHeight = originalHeight,
            heightRows = rowValues.size,
            luma = luma,
        )
    }
}
