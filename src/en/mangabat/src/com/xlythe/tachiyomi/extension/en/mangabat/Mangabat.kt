package com.xlythe.tachiyomi.extension.en.mangabat

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.multisrc.mangabox.MangaBox
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LeadingPageIdentity
import keiyoushi.utils.PageFingerprint
import keiyoushi.utils.PageFingerprintHistory
import keiyoushi.utils.PrefetchedPageImage
import keiyoushi.utils.PrefetchedPageImageStore
import keiyoushi.utils.createPageFingerprint
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.hasDuplicateFingerprintAtEdge
import keiyoushi.utils.initialEdgeFingerprintPositions
import keiyoushi.utils.leadingPagePosition
import keiyoushi.utils.pageIndexForFingerprintPosition
import keiyoushi.utils.reindexPages
import keiyoushi.utils.scanLeadingDuplicates
import keiyoushi.utils.scanTrailingDuplicates
import keiyoushi.utils.trailingPagePosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Mangabat :
    MangaBox(
        "Mangabat",
        arrayOf(
            "www.mangabats.com",
        ),
        "en",
    ) {
    private val prefetchedPageImages = PrefetchedPageImageStore()

    override val client =
        super.client
            .newBuilder()
            .addInterceptor(prefetchedPageImages::intercept)
            .build()

    private val duplicatePagePreferences: SharedPreferences by getPreferencesLazy()
    private val duplicatePageHistory by lazy {
        PageFingerprintHistory(duplicatePagePreferences, DUPLICATE_PAGE_HISTORY_NAMESPACE)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val chapterDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    override fun popularMangaSelector() = MANGA_LIST_SELECTOR

    override fun latestUpdatesSelector() = MANGA_LIST_SELECTOR

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.contains("mangabat.com/")) {
            throw Exception(MIGRATE_MESSAGE)
        }
        return super.mangaDetailsRequest(manga)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug =
            manga.url
                .toHttpUrlOrNull()
                ?.pathSegments
                ?.lastOrNull()
                ?: manga.url.trimEnd('/').substringAfterLast("/")
        return GET("$baseUrl/api/manga/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        json.decodeFromString<MangabatChapterResponse>(response.body.string()).data.chapters.let { chapters ->
            val seriesSlug =
                response.request.url.pathSegments
                    .dropLast(1)
                    .last()
            chapters.map { chapter ->
                SChapter.create().apply {
                    name = chapter.name
                    url = "/manga/$seriesSlug/${chapter.slug}"
                    date_upload = chapter.updatedAt.toChapterDate()
                }
            }
        }

    override fun pageListParse(document: Document): List<Page> {
        val pages = parseRawPageList(document)
        return filterEdgeDuplicatePages(pages) { fetchAdjacentChapterPages(document) }
    }

    private fun parseRawPageList(document: Document): List<Page> = super.pageListParse(document)

    private fun fetchAdjacentChapterPages(document: Document): List<Page>? =
        runCatching {
            val location = document.location().toMangabatChapterLocation() ?: return@runCatching null
            val chapters =
                client.newCall(GET("$baseUrl/api/manga/${location.seriesSlug}/chapters", headers)).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@runCatching null
                    }
                    json.decodeFromString<MangabatChapterResponse>(response.body.string()).data.chapters
                }
            val adjacentSlug =
                chapters.adjacentChapterSlug(location.chapterSlug)
                    ?: return@runCatching null

            client.newCall(GET("$baseUrl/manga/${location.seriesSlug}/$adjacentSlug", headers)).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                parseRawPageList(response.asJsoup())
            }
        }.getOrNull()

    private fun filterEdgeDuplicatePages(
        pages: List<Page>,
        adjacentPagesProvider: () -> List<Page>?,
    ): List<Page> {
        prefetchedPageImages.clear()
        val identity =
            pages
                .firstNotNullOfOrNull { page -> page.imageUrl?.toMangabatPageIdentity() }
                ?: return pages
        val referenceFingerprints =
            duplicatePageHistory
                .previousFingerprints(identity)
                .groupByTo(mutableMapOf()) { it.index }
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.mapTo(mutableListOf()) { it.value } }
        val inspectedPages = mutableMapOf<Int, InspectedEdgePage>()
        val currentFingerprints = linkedMapOf<Int, PageFingerprint>()

        // Always compare against the nearest chapter. Stored history can be incomplete or
        // contain older scan-group cards, while the neighboring chapter is the best source
        // for advertisements that were just added or moved to another edge position.
        val adjacentPages = adjacentPagesProvider()
        val adjacentIdentity =
            adjacentPages
                ?.firstNotNullOfOrNull { page -> page.imageUrl?.toMangabatPageIdentity() }
                ?.takeIf { it.seriesKey == identity.seriesKey && it.chapterKey != identity.chapterKey }
        val inspectedAdjacentPages = mutableMapOf<Int, InspectedEdgePage>()
        val adjacentFingerprints = linkedMapOf<Int, PageFingerprint>()

        fun ensureAdjacentFingerprint(position: Int) {
            val referencePages = adjacentPages ?: return
            if (adjacentIdentity == null || position in adjacentFingerprints) {
                return
            }
            val index = pageIndexForFingerprintPosition(position, referencePages.size) ?: return
            val inspected =
                inspectedAdjacentPages[index]
                    ?: inspectEdgePage(referencePages[index])?.also { inspectedAdjacentPages[index] = it }
                    ?: return
            adjacentFingerprints[position] = inspected.fingerprint
            referenceFingerprints.getOrPut(position, ::mutableListOf) += inspected.fingerprint
        }

        adjacentPages
            ?.takeIf { adjacentIdentity != null }
            ?.let { initialEdgeFingerprintPositions(it.size).forEach(::ensureAdjacentFingerprint) }

        fun isDuplicate(
            index: Int,
            page: Page,
            position: Int,
        ): Boolean {
            ensureAdjacentFingerprint(position)
            val inspected =
                inspectedPages[index]
                    ?: inspectEdgePage(page)?.also { inspectedPages[index] = it }
                    ?: return false
            currentFingerprints[position] = inspected.fingerprint
            return inspected.forceExclude ||
                referenceFingerprints.hasDuplicateFingerprintAtEdge(position, inspected.fingerprint)
        }

        val leadingScan =
            scanLeadingDuplicates(pages) { index, page ->
                isDuplicate(index, page, leadingPagePosition(index))
            }
        val trailingScan =
            scanTrailingDuplicates(pages) { index, page ->
                isDuplicate(index, page, trailingPagePosition(index, pages.size))
            }

        if (adjacentIdentity != null) {
            duplicatePageHistory.recordChapter(
                adjacentIdentity,
                adjacentFingerprints.map { (position, fingerprint) -> IndexedValue(position, fingerprint) },
            )
        }
        duplicatePageHistory.recordChapter(
            identity,
            currentFingerprints.map { (position, fingerprint) -> IndexedValue(position, fingerprint) },
        )
        val inspectedIndices = leadingScan.inspectedIndices + trailingScan.inspectedIndices
        val duplicateIndices =
            (leadingScan.duplicateIndices + trailingScan.duplicateIndices)
                .takeUnless { it.size == pages.size }
                .orEmpty()
        inspectedIndices
            .filterNot(duplicateIndices::contains)
            .forEach { index ->
                val page = pages[index]
                val inspected = inspectedPages[index] ?: return@forEach
                val oppositeEdgeReferences =
                    if (index in leadingScan.inspectedIndices) {
                        trailingScan.inspectedIndices
                    } else {
                        leadingScan.inspectedIndices
                    }.mapNotNull(inspectedPages::get)
                val edgeReferences = inspectedAdjacentPages.values + oppositeEdgeReferences
                val stripReferences = edgeReferences.mapNotNull(InspectedEdgePage::stripProfile)
                val inspectLeading = index in leadingScan.inspectedIndices
                val inspectTrailing = index in trailingScan.inspectedIndices
                val edit =
                    inspected.knownEdit
                        ?: inspected.stripProfile?.generalizedSeamEdit(
                            signatures = inspected.edgeRegionSignatures,
                            references = edgeReferences.flatMap(InspectedEdgePage::edgeRegionSignatures),
                            inspectLeading = inspectLeading,
                            inspectTrailing = inspectTrailing,
                        )
                        ?: inspected.stripProfile?.generalizedEdgeEdit(
                            references = stripReferences,
                            inspectLeading = inspectLeading,
                            inspectTrailing = inspectTrailing,
                        )
                val originalUrl = page.imageUrl ?: return@forEach
                val bounds = edit?.cropBounds(inspected.fingerprint.width, inspected.fingerprint.height)
                val croppedImage = bounds?.let { cropPageImage(inspected.originalImage.bytes, it) }
                val servedUrl =
                    if (croppedImage != null && bounds != null) {
                        originalUrl.withMangabatEdgeCropCacheKey(bounds)
                    } else {
                        originalUrl
                    }
                page.imageUrl = servedUrl
                prefetchedPageImages.put(servedUrl, croppedImage ?: inspected.originalImage)
            }

        return reindexPages(pages, duplicateIndices)
    }

    private fun inspectEdgePage(page: Page): InspectedEdgePage? =
        try {
            client.newCall(imageRequest(page).withFreshMangabatImageInspection()).execute().use { response ->
                if (!response.isSuccessful || response.body.contentLength() > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                val body = response.body
                val bytes = body.bytes()
                if (bytes.size > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                val fingerprint = createPageFingerprint(bytes)
                val edit = fingerprint.sha256.toMangabatEdgePageEdit()
                val stripProfile = createEdgeStripProfile(bytes, fingerprint.width, fingerprint.height)
                InspectedEdgePage(
                    fingerprint = fingerprint,
                    originalImage = PrefetchedPageImage(bytes, body.contentType()?.toString()),
                    stripProfile = stripProfile,
                    edgeRegionSignatures =
                    stripProfile?.let { createEdgeRegionSignatures(bytes, it) }.orEmpty(),
                    knownEdit = edit,
                    forceExclude =
                    edit?.remove == true ||
                        isStructurallyInvalidMangabatEdgeImage(fingerprint.width, fingerprint.height),
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun createEdgeStripProfile(
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): MangabatEdgeStripProfile? {
        if (width <= 0 || height <= 0) {
            return null
        }

        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var scaled: Bitmap? = null
        return try {
            val scaledHeight = maxOf(1, (height * EDGE_STRIP_PROFILE_WIDTH + width / 2) / width)
            scaled = Bitmap.createScaledBitmap(source, EDGE_STRIP_PROFILE_WIDTH, scaledHeight, true)
            val pixels = IntArray(EDGE_STRIP_PROFILE_WIDTH * scaledHeight)
            scaled.getPixels(pixels, 0, EDGE_STRIP_PROFILE_WIDTH, 0, 0, EDGE_STRIP_PROFILE_WIDTH, scaledHeight)
            val luma =
                ByteArray(pixels.size) { index ->
                    val color = pixels[index]
                    val red = color shr 16 and 0xFF
                    val green = color shr 8 and 0xFF
                    val blue = color and 0xFF
                    ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
                }
            MangabatEdgeStripProfile(width, height, scaledHeight, luma)
        } finally {
            if (scaled !== source) {
                scaled?.recycle()
            }
            source.recycle()
        }
    }

    private fun createEdgeRegionSignatures(
        bytes: ByteArray,
        profile: MangabatEdgeStripProfile,
    ): List<MangabatEdgeRegionSignature> {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        return try {
            buildList {
                listOf(true, false).forEach { fromTop ->
                    profile.strongEdgeBoundaryRows(fromTop).forEach { edgeRows ->
                        val pixelHeight = profile.normalizedRowsToPixels(edgeRows)
                        val top = if (fromTop) 0 else source.height - pixelHeight
                        source.blockAverageHash(top, pixelHeight)?.let { hash ->
                            add(MangabatEdgeRegionSignature(edgeRows, fromTop, hash))
                        }
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }
    private fun cropPageImage(
        bytes: ByteArray,
        bounds: MangabatCropBounds,
    ): PrefetchedPageImage? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var cropped: Bitmap? = null
        return try {
            cropped = Bitmap.createBitmap(source, bounds.x, bounds.y, bounds.width, bounds.height)
            val output = ByteArrayOutputStream()
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                PrefetchedPageImage(output.toByteArray(), "image/png")
            }
        } finally {
            if (cropped !== source) {
                cropped?.recycle()
            }
            source.recycle()
        }
    }

    private fun String.toChapterDate(): Long =
        runCatching {
            val normalized = replace(Regex("""\.(\d{3})\d*Z$"""), ".$1Z")
            chapterDateFormat.parse(normalized)?.time ?: 0L
        }.getOrDefault(0L)

    companion object {
        internal const val MANGA_LIST_SELECTOR =
            "div.truyen-list > div.list-truyen-item-wrap:has(a[data-id]), " +
                "div.comic-list > .list-comic-item-wrap:has(a[data-id])"
        private const val MIGRATE_MESSAGE = "Migrate this entry from \"Mangabat\" to \"Mangabat\" to continue reading"
        private const val DUPLICATE_PAGE_HISTORY_NAMESPACE = "mangabat.leading_pages.v1"
        private const val MAX_FINGERPRINT_IMAGE_BYTES = 16 * 1024 * 1024
        private const val EDGE_STRIP_PROFILE_WIDTH = 32
    }
}

@Serializable
internal data class MangabatChapterResponse(
    val data: MangabatChapterData,
)

@Serializable
internal data class MangabatChapterData(
    val chapters: List<MangabatChapter>,
)

@Serializable
internal data class MangabatChapter(
    @SerialName("chapter_name")
    val name: String,
    @SerialName("chapter_slug")
    val slug: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

private data class InspectedEdgePage(
    val fingerprint: PageFingerprint,
    val originalImage: PrefetchedPageImage,
    val stripProfile: MangabatEdgeStripProfile?,
    val edgeRegionSignatures: List<MangabatEdgeRegionSignature>,
    val knownEdit: MangabatEdgePageEdit?,
    val forceExclude: Boolean,
)

internal fun Request.withFreshMangabatImageInspection(): Request =
    newBuilder()
        .url(
            url.newBuilder()
                .setQueryParameter(MANGABAT_EDGE_INSPECTION_QUERY_PARAMETER, "v1")
                .build(),
        )
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()
internal data class MangabatEdgeStripProfile(
    val originalWidth: Int,
    val originalHeight: Int,
    val heightRows: Int,
    val luma: ByteArray,
) {
    val profileWidth: Int
        get() = if (heightRows > 0) luma.size / heightRows else 0
}

internal data class MangabatEdgeRegionSignature(
    val edgeRows: Int,
    val fromTop: Boolean,
    val hash: ByteArray,
) {
    fun isDuplicateOf(other: MangabatEdgeRegionSignature): Boolean {
        if (
            kotlin.math.abs(edgeRows - other.edgeRows) > MAXIMUM_EDGE_SIGNATURE_ROW_DELTA ||
            hash.size != other.hash.size
        ) {
            return false
        }
        var distance = 0
        for (index in hash.indices) {
            if (hash[index] != other.hash[index] && ++distance > MAXIMUM_EDGE_SIGNATURE_DISTANCE) {
                return false
            }
        }
        return true
    }
}

internal fun MangabatEdgeStripProfile.generalizedSeamEdit(
    signatures: List<MangabatEdgeRegionSignature>,
    references: List<MangabatEdgeRegionSignature>,
    inspectLeading: Boolean,
    inspectTrailing: Boolean,
): MangabatEdgePageEdit? {
    fun matchingRows(fromTop: Boolean): Int =
        signatures
            .asSequence()
            .filter { it.fromTop == fromTop }
            .filter { candidate -> references.any(candidate::isDuplicateOf) }
            .maxOfOrNull(MangabatEdgeRegionSignature::edgeRows)
            ?: 0

    val topRows = if (inspectLeading) matchingRows(fromTop = true) else 0
    val bottomRows = if (inspectTrailing) matchingRows(fromTop = false) else 0
    var topPixels = normalizedRowsToPixels(topRows)
    var bottomPixels = normalizedRowsToPixels(bottomRows)
    val minimumRetainedHeight = maxOf(originalWidth / 3, MINIMUM_RETAINED_EDGE_PIXELS)
    if (originalHeight - topPixels - bottomPixels < minimumRetainedHeight) {
        if (topPixels >= bottomPixels && originalHeight - topPixels >= minimumRetainedHeight) {
            bottomPixels = 0
        } else if (originalHeight - bottomPixels >= minimumRetainedHeight) {
            topPixels = 0
        } else {
            return null
        }
    }
    if (topPixels == 0 && bottomPixels == 0) return null
    return MangabatEdgePageEdit(
        topOffset = topPixels,
        retainedHeight = bottomPixels.takeIf { it > 0 }?.let { originalHeight - topPixels - it },
    )
}

private fun MangabatEdgeStripProfile.strongEdgeBoundaryRows(fromTop: Boolean): List<Int> {
    val maximumRows = minOf(profileWidth, heightRows * MAXIMUM_EDGE_CROP_NUMERATOR / MAXIMUM_EDGE_CROP_DENOMINATOR)
    return (MINIMUM_REPEATED_EDGE_ROWS..maximumRows).filter { edgeRows ->
        val boundaryRow = if (fromTop) edgeRows else heightRows - edgeRows
        val startRow = if (fromTop) 0 else heightRows - edgeRows
        boundaryRow > 0 &&
            boundaryRow < heightRows &&
            hasVisualDetail(startRow, edgeRows) &&
            horizontalRowDifference(boundaryRow - 1, boundaryRow) >=
                MINIMUM_STRONG_EDGE_SEAM_MEAN_DIFFERENCE * profileWidth
    }
}

private fun Bitmap.blockAverageHash(top: Int, height: Int): ByteArray? {
    if (top < 0 || height <= 0 || top + height > this.height || width <= 0) return null
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, top, width, height)
    val averages = LongArray(EDGE_SIGNATURE_GRID_SIZE * EDGE_SIGNATURE_GRID_SIZE)
    var total = 0L
    for (gridY in 0 until EDGE_SIGNATURE_GRID_SIZE) {
        val yStart = gridY * height / EDGE_SIGNATURE_GRID_SIZE
        val yEnd = (gridY + 1) * height / EDGE_SIGNATURE_GRID_SIZE
        for (gridX in 0 until EDGE_SIGNATURE_GRID_SIZE) {
            val xStart = gridX * width / EDGE_SIGNATURE_GRID_SIZE
            val xEnd = (gridX + 1) * width / EDGE_SIGNATURE_GRID_SIZE
            var sum = 0L
            var count = 0
            for (y in yStart until yEnd) {
                for (x in xStart until xEnd) {
                    val color = pixels[y * width + x]
                    sum +=
                        (
                        (color shr 16 and 0xFF) * 299 +
                            (color shr 8 and 0xFF) * 587 +
                            (color and 0xFF) * 114
                        ) / 1000
                    count++
                }
            }
            if (count == 0) return null
            val index = gridY * EDGE_SIGNATURE_GRID_SIZE + gridX
            averages[index] = sum / count
            total += averages[index]
        }
    }
    return ByteArray(averages.size) { index ->
        if (averages[index] * averages.size > total) 1 else 0
    }
}
internal fun isStructurallyInvalidMangabatEdgeImage(
    width: Int,
    height: Int,
): Boolean = width <= 1 || height <= 1

internal fun MangabatEdgeStripProfile.generalizedEdgeEdit(
    references: List<MangabatEdgeStripProfile>,
    inspectLeading: Boolean,
    inspectTrailing: Boolean,
): MangabatEdgePageEdit? {
    if (profileWidth <= 0 || originalWidth <= 0 || originalHeight <= 0) {
        return null
    }

    val compatibleReferences = references.filter { it.profileWidth == profileWidth && it !== this }
    val topRows =
        if (inspectLeading) {
            repeatedEdgeRows(compatibleReferences, fromTop = true)
        } else {
            0
        }
    val bottomRows =
        if (inspectTrailing) {
            repeatedEdgeRows(compatibleReferences, fromTop = false)
        } else {
            0
        }
    var topPixels = normalizedRowsToPixels(topRows)
    var bottomPixels = normalizedRowsToPixels(bottomRows)
    val minimumRetainedHeight = maxOf(originalWidth / 3, MINIMUM_RETAINED_EDGE_PIXELS)

    if (originalHeight - topPixels - bottomPixels < minimumRetainedHeight) {
        if (topPixels >= bottomPixels && originalHeight - topPixels >= minimumRetainedHeight) {
            bottomPixels = 0
        } else if (originalHeight - bottomPixels >= minimumRetainedHeight) {
            topPixels = 0
        } else {
            return null
        }
    }
    if (topPixels == 0 && bottomPixels == 0) {
        return null
    }

    return MangabatEdgePageEdit(
        topOffset = topPixels,
        retainedHeight =
        bottomPixels
            .takeIf { it > 0 }
            ?.let { originalHeight - topPixels - it },
    )
}

private fun MangabatEdgeStripProfile.repeatedEdgeRows(
    references: List<MangabatEdgeStripProfile>,
    fromTop: Boolean,
): Int {
    val minimumRows = maxOf(MINIMUM_REPEATED_EDGE_ROWS, profileWidth / 4)
    val maximumRows =
        minOf(
            profileWidth,
            heightRows * MAXIMUM_EDGE_CROP_NUMERATOR / MAXIMUM_EDGE_CROP_DENOMINATOR,
            references.maxOfOrNull(MangabatEdgeStripProfile::heightRows) ?: 0,
        )
    if (maximumRows < minimumRows) {
        return 0
    }

    var bestRows = 0
    for (rows in minimumRows..maximumRows) {
        val candidateStart = if (fromTop) 0 else heightRows - rows
        if (!hasVisualDetail(candidateStart, rows)) {
            continue
        }
        val matched =
            references.any { reference ->
                if (reference.heightRows < rows) {
                    false
                } else {
                    (0..reference.heightRows - rows).any { referenceStart ->
                        reference.hasVisualDetail(referenceStart, rows) &&
                            regionsMatch(candidateStart, reference, referenceStart, rows, fromTop)
                    }
                }
            }
        if (matched) {
            bestRows = rows
        }
    }
    return expandToNearbyBoundary(bestRows, fromTop, maximumRows)
}

private fun MangabatEdgeStripProfile.expandToNearbyBoundary(
    matchedRows: Int,
    fromTop: Boolean,
    maximumRows: Int,
): Int {
    if (matchedRows <= 0) {
        return 0
    }

    val searchEnd = minOf(maximumRows, matchedRows + MAXIMUM_EDGE_BOUNDARY_SEARCH_ROWS)
    var bestRows = matchedRows
    var bestDifference = MINIMUM_EDGE_BOUNDARY_MEAN_DIFFERENCE * profileWidth - 1
    for (cropRows in matchedRows..searchEnd) {
        val boundaryRow = if (fromTop) cropRows else heightRows - cropRows
        if (boundaryRow <= 0 || boundaryRow >= heightRows) {
            continue
        }
        val difference = horizontalRowDifference(boundaryRow - 1, boundaryRow)
        if (difference >= bestDifference) {
            bestDifference = difference
            bestRows = cropRows
        }
    }
    return bestRows
}

private fun MangabatEdgeStripProfile.horizontalRowDifference(
    firstRow: Int,
    secondRow: Int,
): Long {
    var difference = 0L
    val firstStart = firstRow * profileWidth
    val secondStart = secondRow * profileWidth
    for (column in 0 until profileWidth) {
        val first = luma[firstStart + column].toInt() and 0xFF
        val second = luma[secondStart + column].toInt() and 0xFF
        difference += kotlin.math.abs(first - second)
    }
    return difference
}

private fun MangabatEdgeStripProfile.hasVisualDetail(
    startRow: Int,
    rows: Int,
): Boolean {
    var minimum = 255
    var maximum = 0
    val start = startRow * profileWidth
    val end = (startRow + rows) * profileWidth
    for (index in start until end) {
        val value = luma[index].toInt() and 0xFF
        minimum = minOf(minimum, value)
        maximum = maxOf(maximum, value)
    }
    return maximum - minimum >= MINIMUM_EDGE_LUMA_RANGE
}

private fun MangabatEdgeStripProfile.regionsMatch(
    startRow: Int,
    reference: MangabatEdgeStripProfile,
    referenceStartRow: Int,
    rows: Int,
    fromTop: Boolean,
): Boolean {
    val pixelCount = rows * profileWidth
    var difference = 0L
    val start = startRow * profileWidth
    val referenceStart = referenceStartRow * profileWidth
    for (row in 0 until rows) {
        var rowDifference = 0L
        for (column in 0 until profileWidth) {
            val offset = row * profileWidth + column
            val left = luma[start + offset].toInt() and 0xFF
            val right = reference.luma[referenceStart + offset].toInt() and 0xFF
            rowDifference += kotlin.math.abs(left - right)
        }
        val isOuterEdgeSlack =
            if (fromTop) {
                row < OUTER_EDGE_MISMATCH_ROWS
            } else {
                row >= rows - OUTER_EDGE_MISMATCH_ROWS
            }
        if (!isOuterEdgeSlack && rowDifference > MAXIMUM_EDGE_ROW_MEAN_DIFFERENCE * profileWidth) {
            return false
        }
        difference += rowDifference
    }
    return difference <= MAXIMUM_EDGE_MEAN_DIFFERENCE * pixelCount
}

private fun MangabatEdgeStripProfile.normalizedRowsToPixels(rows: Int): Int =
    if (rows <= 0) {
        0
    } else {
        (rows * originalWidth + profileWidth - 1) / profileWidth
    }

private const val EDGE_SIGNATURE_GRID_SIZE = 16
private const val MAXIMUM_EDGE_SIGNATURE_DISTANCE = 72
private const val MAXIMUM_EDGE_SIGNATURE_ROW_DELTA = 4
private const val MINIMUM_STRONG_EDGE_SEAM_MEAN_DIFFERENCE = 128L
private const val MINIMUM_REPEATED_EDGE_ROWS = 8
private const val MINIMUM_EDGE_LUMA_RANGE = 48
private const val MAXIMUM_EDGE_MEAN_DIFFERENCE = 20L
private const val MAXIMUM_EDGE_ROW_MEAN_DIFFERENCE = 48L
private const val OUTER_EDGE_MISMATCH_ROWS = 1
private const val MAXIMUM_EDGE_BOUNDARY_SEARCH_ROWS = 8
private const val MINIMUM_EDGE_BOUNDARY_MEAN_DIFFERENCE = 64L
private const val MAXIMUM_EDGE_CROP_NUMERATOR = 2
private const val MAXIMUM_EDGE_CROP_DENOMINATOR = 3
private const val MINIMUM_RETAINED_EDGE_PIXELS = 256
private const val MANGABAT_EDGE_INSPECTION_QUERY_PARAMETER = "tachiyomi_edge_inspection"
private const val MANGABAT_EDGE_CROP_QUERY_PARAMETER = "tachiyomi_edge_crop"

internal data class MangabatCropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun String.withMangabatEdgeCropCacheKey(bounds: MangabatCropBounds): String =
    toHttpUrlOrNull()
        ?.newBuilder()
        ?.setQueryParameter(
            MANGABAT_EDGE_CROP_QUERY_PARAMETER,
            "v4-${bounds.x}-${bounds.y}-${bounds.width}-${bounds.height}",
        )
        ?.build()
        ?.toString()
        ?: this

internal data class MangabatEdgePageEdit(
    val remove: Boolean = false,
    val topOffset: Int = 0,
    val retainedHeight: Int? = null,
) {
    fun cropBounds(
        imageWidth: Int,
        imageHeight: Int,
    ): MangabatCropBounds? {
        if (remove || topOffset == 0 && retainedHeight == null) {
            return null
        }

        val height = retainedHeight ?: (imageHeight - topOffset)
        return MangabatCropBounds(0, topOffset, imageWidth, height)
            .takeIf {
                imageWidth > 0 &&
                    topOffset >= 0 &&
                    height > 0 &&
                    topOffset + height <= imageHeight
            }
    }
}

internal fun String.toMangabatEdgePageEdit(): MangabatEdgePageEdit? =
    KNOWN_MANGABAT_EDGE_PAGE_EDITS[lowercase(Locale.ROOT)]

private val KNOWN_MANGABAT_EDGE_PAGE_EDITS =
    mapOf(
        // Barbarian's Adventure in a Fantasy World, chapter 66: donation page.
        "613b33ef64a03537477d080305788367b14468d6bbbc2fb58e8740f4ce84d6ff" to
            MangabatEdgePageEdit(remove = true),
        // The staff card and whitespace precede the first real panel in the same image.
        "0ddd9b6cd92872567fc4cf0a06aec285c29fe8386f4e7671221525cef60fadec" to
            MangabatEdgePageEdit(topOffset = 1024),
        // Preserve the final speech panel, cutting only the blank/promo tail beneath it.
        "6039c39c95974e54c3eb850649b4c0fc6689f0865b054c42fbc7023f94a5ec47" to
            MangabatEdgePageEdit(retainedHeight = 425),
        // Full-page scan-group promotion after the chapter has ended.
        "58593fee88b74a204572f1607791f92cc4cc7e64843c58c1fa7237ef7ad71b62" to
            MangabatEdgePageEdit(remove = true),
    )

internal data class MangabatChapterLocation(
    val seriesSlug: String,
    val chapterSlug: String,
)

internal fun String.toMangabatChapterLocation(): MangabatChapterLocation? {
    val pathSegments =
        toHttpUrlOrNull()
            ?.pathSegments
            ?.filter(String::isNotBlank)
            ?: return null
    val mangaIndex = pathSegments.indexOf("manga")
    val seriesSlug = pathSegments.getOrNull(mangaIndex + 1)?.takeIf(String::isNotBlank) ?: return null
    val chapterSlug = pathSegments.getOrNull(mangaIndex + 2)?.takeIf(String::isNotBlank) ?: return null
    return MangabatChapterLocation(seriesSlug, chapterSlug)
}

internal fun List<MangabatChapter>.adjacentChapterSlug(currentChapterSlug: String): String? {
    val currentIndex = indexOfFirst { it.slug == currentChapterSlug }
    if (currentIndex < 0) {
        return null
    }
    return getOrNull(currentIndex + 1)?.slug ?: getOrNull(currentIndex - 1)?.slug
}

internal fun String.toMangabatPageIdentity(): LeadingPageIdentity? {
    val pathSegments =
        toHttpUrlOrNull()
            ?.pathSegments
            ?.filter(String::isNotBlank)
            ?: return null
    if (pathSegments.size < 3) {
        return null
    }

    val seriesKey = pathSegments.dropLast(2).joinToString("/")
    val chapterKey = pathSegments[pathSegments.lastIndex - 1]
    return LeadingPageIdentity(seriesKey, chapterKey)
}
