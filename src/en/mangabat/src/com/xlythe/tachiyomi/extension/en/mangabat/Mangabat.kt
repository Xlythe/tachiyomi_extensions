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
            ?.let { mangabatFingerprintPositions(it.size).forEach(::ensureAdjacentFingerprint) }

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
            val normalizedFullPageDuplicate =
                position >= 0 &&
                    inspected.fullPageSignature?.let { candidate ->
                    inspectedAdjacentPages
                        .asSequence()
                        .filter { (adjacentIndex) -> adjacentIndex < MANGABAT_EARLY_PAGE_WINDOW }
                        .mapNotNull { (_, reference) -> reference.fullPageSignature }
                        .any(candidate::isDuplicateOf)
                } == true
            return inspected.forceExclude ||
                referenceFingerprints.hasDuplicateFingerprintAtEdge(position, inspected.fingerprint) ||
                position >= 0 && referenceFingerprints.hasShiftedMangabatOpeningDuplicate(inspected.fingerprint) ||
                normalizedFullPageDuplicate
        }

        // Scan a bounded opening window even after real pages appear. Scan groups often
        // insert a repeated title card one or two pages into the chapter, so a sequential
        // edge scan alone stops too early. Cross-position removal still requires an exact
        // content hash, or the strict scaled-card matcher used for chapter-specific canvases.
        val earlyScan =
            scanMangabatEarlyDuplicates(pages) { index, page ->
                isDuplicate(index, page, leadingPagePosition(index))
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
        val leadingInspectedIndices = earlyScan.inspectedIndices + leadingScan.inspectedIndices
        val inspectedIndices = leadingInspectedIndices + trailingScan.inspectedIndices
        val duplicateIndices =
            (earlyScan.duplicateIndices + leadingScan.duplicateIndices + trailingScan.duplicateIndices)
                .takeUnless { it.size == pages.size }
                .orEmpty()
        inspectedIndices
            .filterNot(duplicateIndices::contains)
            .forEach { index ->
                val page = pages[index]
                val inspected = inspectedPages[index] ?: return@forEach
                val oppositeEdgeReferences =
                    if (index in leadingInspectedIndices) {
                        trailingScan.inspectedIndices
                    } else {
                        leadingInspectedIndices
                    }.mapNotNull(inspectedPages::get)
                val edgeReferences = inspectedAdjacentPages.values + oppositeEdgeReferences
                val stripReferences = edgeReferences.mapNotNull(InspectedEdgePage::stripProfile)
                val inspectLeading = index in leadingInspectedIndices
                val inspectTrailing = index in trailingScan.inspectedIndices
                val seamEdit =
                    inspected.stripProfile?.generalizedSeamEdit(
                        signatures = inspected.edgeRegionSignatures,
                        references = edgeReferences.flatMap(InspectedEdgePage::edgeRegionSignatures),
                        inspectLeading = inspectLeading,
                        inspectTrailing = inspectTrailing,
                    )
                val stripEdit =
                    inspected.stripProfile?.generalizedEdgeEdit(
                        references = stripReferences,
                        inspectLeading = inspectLeading,
                        inspectTrailing = inspectTrailing,
                    )
                val edit =
                    inspected.knownEdit
                        ?: agreeingMangabatEdgeEdit(
                            seamEdit,
                            stripEdit,
                            inspected.fingerprint.width,
                            inspected.fingerprint.height,
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
                    fullPageSignature = createMangabatFullPageSignature(bytes),
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

    private fun createMangabatFullPageSignature(bytes: ByteArray): MangabatFullPageSignature? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            source.blockAverageHash(0, source.height)?.let { hash ->
                MangabatFullPageSignature(source.width, source.height, hash)
            }
        } finally {
            source.recycle()
        }
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
    val fullPageSignature: MangabatFullPageSignature?,
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

internal data class MangabatFullPageSignature(
    val width: Int,
    val height: Int,
    val hash: ByteArray,
) {
    fun isDuplicateOf(other: MangabatFullPageSignature): Boolean {
        if (width <= 0 || width != other.width || height <= 0 || other.height <= 0 || hash.size != other.hash.size) {
            return false
        }
        val heightRatio = maxOf(height, other.height).toDouble() / minOf(height, other.height)
        if (heightRatio > MANGABAT_FULL_PAGE_MAXIMUM_HEIGHT_RATIO) {
            return false
        }
        var distance = 0
        for (index in hash.indices) {
            if (hash[index] != other.hash[index] && ++distance > MANGABAT_FULL_PAGE_SIGNATURE_DISTANCE) {
                return false
            }
        }
        return true
    }
}
internal data class MangabatEdgeRegionSignature(
    val edgeRows: Int,
    val fromTop: Boolean,
    val hash: ByteArray,
) {
    fun isDuplicateOf(other: MangabatEdgeRegionSignature): Boolean {
        if (
            fromTop != other.fromTop ||
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
                    val referenceStart = if (fromTop) 0 else reference.heightRows - rows
                    reference.hasVisualDetail(referenceStart, rows) &&
                        regionsMatch(candidateStart, reference, referenceStart, rows, fromTop)
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

private const val MANGABAT_FULL_PAGE_SIGNATURE_DISTANCE = 32
private const val MANGABAT_FULL_PAGE_MAXIMUM_HEIGHT_RATIO = 1.2
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
        // Global scan-group donation notice; unique among the surrounding 36 chapters.
        "380976af8896613cf41c8baf933150f1566608bbce7d2512de1016e81c58a735" to
            MangabatEdgePageEdit(remove = true),
        // Global scan-group donation explainer; remove only this exact standalone page.
        "3dad81b91bab96c116b000d1858584f1ff97f217c89ec4418f9c62e02f9cfbb4" to
            MangabatEdgePageEdit(remove = true),
        // Continuation pages from the same global donation solicitation.
        "440aa8e557171633e95dfc62be2c599dc08523a5a60d35b0d45ee0362ce3f213" to
            MangabatEdgePageEdit(remove = true),
        "f5646528dd39539bf2eeff1012f5595cf6ab2fed9aa31575e0f3e9ef18f95889" to
            MangabatEdgePageEdit(remove = true),
        // Preserve the real panel above the standalone scan-group title card.
        "ce3d5b01101395f9732572ddc166ea514c2ac30486766e1d5afe994e99f6de7d" to
            MangabatEdgePageEdit(retainedHeight = 715),
        // Preserve the real dialogue below the second half of the title card.
        "485b505800f236d2021e0069b2906cc5c3cce0c3c94c8d4863aca09c8a05416b" to
            MangabatEdgePageEdit(topOffset = 545),
        // Standalone scan-group announcement; remove only this exact global content hash.
        "96267e22823639ca84cf4b0a20f2a3836d122b56bfe76da93758dc84148c48ab" to
            MangabatEdgePageEdit(remove = true),
        // The same announcement is stitched above genuine comic content in another upload.
        "350e5adee50348d8265f093b2b89e8a85c9ecd975929999dcfa496387f53b674" to
            MangabatEdgePageEdit(topOffset = 470),
        // Standalone promo and donation pages; exact global content hashes only.
        "a4773fe64cd1ad1755c917dc9b25989b4d5d37f26535240818a39797db41c12b" to
            MangabatEdgePageEdit(remove = true),
        "f87af2f14ac21ebb326c0141ce113cd37f17f9a8875ec4832897efac09fb1f50" to
            MangabatEdgePageEdit(remove = true),
        // Preserve genuine panels around a promo spill and split scan-group title card.
        "063047489f33ffb0cf11bd57edccc3cdca9c2dfd6df7c77457755f0c74c43663" to
            MangabatEdgePageEdit(topOffset = 130),
        "eff3515ade6737a44348a7c88027fda5b3fc2068c66d5e5e9393f2a14c523f93" to
            MangabatEdgePageEdit(retainedHeight = 940),
        "0a6f7ee3beca4d8c2de0cf196e1a9b694c19ce166dfbc836e2123d512f27f342" to
            MangabatEdgePageEdit(topOffset = 640),
        // Recruitment banners stitched above real comic content.
        "a4ed10cb1dd8f175ffd6932d5e0d6a933b4efef2ced0f49b840c7133b131394e" to
            MangabatEdgePageEdit(topOffset = 480),
        "3d2c87c8cc0c28a5ba5ce25e627cb6adcd5d99935ca479b512dc29489cfbd080" to
            MangabatEdgePageEdit(topOffset = 440),
        // Long-form donation explainer from the same scan group.
        "289b09d0300c8233b51b3f34960293cfe306b64aa15621e0485f52c31108e638" to
            MangabatEdgePageEdit(remove = true),
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
        // Demonic Scans "read this chapter at" solicitation. The exact content hash
        // keeps the removal global while avoiding visual guesses on legitimate endings.
        "28bac9126d70960bdb5ba62e6408f8862df470424b2cd483abff2fd5c7d600e7" to
            MangabatEdgePageEdit(remove = true),
    )

internal fun agreeingMangabatEdgeEdit(
    seamEdit: MangabatEdgePageEdit?,
    stripEdit: MangabatEdgePageEdit?,
    imageWidth: Int,
    imageHeight: Int,
): MangabatEdgePageEdit? {
    val seamBounds = seamEdit?.cropBounds(imageWidth, imageHeight) ?: return null
    val stripBounds = stripEdit?.cropBounds(imageWidth, imageHeight) ?: return null
    return seamEdit.takeIf { seamBounds == stripBounds }
}

private const val MANGABAT_SHIFTED_CARD_HASH_THRESHOLD = 4
private const val MANGABAT_SHIFTED_CARD_MAXIMUM_HEIGHT_RATIO = 1.2

internal fun PageFingerprint.isShiftedMangabatOpeningDuplicateOf(other: PageFingerprint): Boolean {
    val candidateHash = differenceHash ?: return false
    val referenceHash = other.differenceHash ?: return false
    if (width <= 0 || width != other.width || height <= 0 || other.height <= 0) {
        return false
    }
    val heightRatio = maxOf(height, other.height).toDouble() / minOf(height, other.height)
    return heightRatio <= MANGABAT_SHIFTED_CARD_MAXIMUM_HEIGHT_RATIO &&
        java.lang.Long.bitCount(candidateHash xor referenceHash) <= MANGABAT_SHIFTED_CARD_HASH_THRESHOLD
}

internal fun Map<Int, List<PageFingerprint>>.hasShiftedMangabatOpeningDuplicate(candidate: PageFingerprint): Boolean =
    entries
        .asSequence()
        .filter { (position) -> position >= 0 }
        .flatMap { (_, fingerprints) -> fingerprints.asSequence() }
        .any(candidate::isShiftedMangabatOpeningDuplicateOf)

internal const val MANGABAT_EARLY_PAGE_WINDOW = 12

internal fun mangabatFingerprintPositions(pageCount: Int): List<Int> {
    val early = (0 until minOf(pageCount, MANGABAT_EARLY_PAGE_WINDOW)).map(::leadingPagePosition)
    val trailing = initialEdgeFingerprintPositions(pageCount).filter { it < 0 }
    return early + trailing
}

internal fun <T> scanMangabatEarlyDuplicates(
    items: List<T>,
    isDuplicate: (index: Int, item: T) -> Boolean,
): keiyoushi.utils.LeadingDuplicateScanResult {
    val inspected = (0 until minOf(items.size, MANGABAT_EARLY_PAGE_WINDOW)).toSet()
    val duplicates = inspected.filterTo(linkedSetOf()) { index -> isDuplicate(index, items[index]) }
    return keiyoushi.utils.LeadingDuplicateScanResult(inspected, duplicates)
}

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
