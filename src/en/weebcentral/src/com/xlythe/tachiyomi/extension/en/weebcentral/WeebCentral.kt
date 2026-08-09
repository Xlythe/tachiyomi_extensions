package com.xlythe.tachiyomi.extension.en.weebcentral

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
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
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.ByteArrayOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class WeebCentral : ParsedHttpSource() {
    override val name = "Weeb Central"

    override val baseUrl = "https://weebcentral.com"

    override val lang = "en"

    override val supportsLatest = true

    private val prefetchedPageImages = PrefetchedPageImageStore(maxBytes = 64 * 1024 * 1024)

    override val client =
        network.cloudflareClient
            .newBuilder()
            .addInterceptor(prefetchedPageImages::intercept)
            .rateLimit(2)
            .build()

    override fun headersBuilder() =
        super
            .headersBuilder()
            .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

    private val excludedSearchCharacters = "[!#:()]".toRegex()

    private val duplicatePagePreferences: SharedPreferences by getPreferencesLazy()
    private val duplicatePageHistory by lazy {
        PageFingerprintHistory(duplicatePagePreferences, DUPLICATE_PAGE_HISTORY_NAMESPACE)
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request =
        searchMangaRequest(
            page,
            "",
            defaultFilterList(SortFilter("Popularity")),
        )

    override fun popularMangaSelector(): String = searchMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String = searchMangaNextPageSelector()

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response.asJsoup())

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        searchMangaRequest(
            page,
            "",
            defaultFilterList(SortFilter("Latest Updates")),
        )

    override fun latestUpdatesSelector(): String = searchMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = searchMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response.asJsoup())

    // =============================== Search ===============================

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        val pathSegment =
            query
                .takeIf { it.startsWith(URL_SEARCH_PREFIX) }
                ?.removePrefix(URL_SEARCH_PREFIX)
                ?: return super.fetchSearchManga(page, query, filters)

        return client
            .newCall(mangaDetailsRequest(SManga.create().apply { url = "/series/$pathSegment" }))
            .asObservableSuccess()
            .map { MangasPage(listOf(mangaDetailsParse(it).apply { initialized = true }), false) }
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val filterList = filters.ifEmpty { getFilterList() }
        val url =
            "$baseUrl/search/data"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addQueryParameter("text", query.replace(excludedSearchCharacters, " ").trim())
                    filterList.filterIsInstance<UriFilter>().forEach {
                        it.addToUri(this)
                    }
                    addQueryParameter("limit", FETCH_LIMIT.toString())
                    addQueryParameter("offset", ((page - 1) * FETCH_LIMIT).toString())
                    addQueryParameter("display_mode", "Full Display")
                }.build()

        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = "article > section > a"

    override fun searchMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            thumbnail_url = element.sourceImg()
            title = element.weebCentralTitle(WEEBCENTRAL_LIST_TITLE_SELECTOR)
            setUrlWithoutDomain(element.absUrl("href"))
        }

    override fun searchMangaNextPageSelector(): String = "button"

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response.asJsoup())

    private fun mangaListParse(document: Document): MangasPage =
        MangasPage(
            mangas =
            document
                .selectWeebCentralMangaElements(searchMangaSelector())
                .map(::searchMangaFromElement),
            hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null,
        )

    // =============================== Filters ==============================

    override fun getFilterList(): FilterList = defaultFilterList(SortFilter())

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(document: Document): SManga =
        SManga.create().apply {
            val descBuilder = StringBuilder()

            with(document.select("section[x-data] > section")[0]) {
                thumbnail_url = sourceImg()
                author = select("ul > li:has(strong:contains(Author)) > span > a").joinToString { it.text() }
                genre = select("ul > li:has(strong:contains(Tag),strong:contains(Type)) a").joinToString { it.text() }
                status = selectFirst("ul > li:has(strong:contains(Status)) > a").parseStatus()
            }

            with(document.select("section[x-data] > section")[1]) {
                title = weebCentralTitle("h1")

                descBuilder.append(
                    selectFirst("li:has(strong:contains(Description)) > p")
                        ?.text()
                        ?.replace("NOTE: ", "\n\nNOTE: "),
                )

                val relatedSeries = select("li:has(strong:contains(Related Series)) li")
                if (relatedSeries.size > 0) {
                    descBuilder.append("\n\nRelated Series(s):")
                    relatedSeries.forEach { series ->
                        descBuilder.append("\n").append("• ${series.text()}")
                    }
                }

                val alternateTitles = select("li:has(strong:contains(Associated Name)) li")
                if (alternateTitles.size > 0) {
                    descBuilder.append("\n\nAssociated Name(s):")
                    alternateTitles.forEach { descBuilder.append("\n").append("• ${it.text()}") }
                }
            }

            description = descBuilder.toString()

            setUrlWithoutDomain(document.location())
        }

    private fun Element?.parseStatus(): Int =
        when (this?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "complete" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val url =
            (baseUrl + manga.url)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    removePathSegment(2)
                    addPathSegment("full-chapter-list")
                }.build()

        return GET(url, headers)
    }

    override fun chapterListSelector() = "div[x-data] > a"

    override fun chapterFromElement(element: Element): SChapter =
        SChapter.create().apply {
            name = element.selectFirst("span.flex > span")!!.text()
            setUrlWithoutDomain(element.attr("abs:href"))
            element.selectFirst("time[datetime]")?.also {
                date_upload = it.attr("datetime").parseDate()
            }
            element.selectFirst("svg")?.attr("stroke")?.also { stroke ->
                scanlator =
                    when (stroke) {
                        "#d8b4fe" -> "Official"
                        "#4C4D54" -> "Unknown"
                        else -> null
                    }
            }
        }

    private fun String.parseDate(): Long =
        try {
            dateFormat.parse(this)!!.time
        } catch (_: ParseException) {
            0L
        }
    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        val newUrl =
            (baseUrl + chapter.url)
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment("images")
                ?.addQueryParameter("is_prev", "False")
                ?.addQueryParameter("reading_style", "long_strip")
                ?.build()
                ?.toString()
                ?: (baseUrl + chapter.url)
        return GET(newUrl, headers)
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListParse(document: Document): List<Page> {
        val pages = parsePageList(document)
        return filterEdgeDuplicatePages(pages) { fetchAdjacentChapterPages(document) }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders =
            headersBuilder()
                .apply {
                    add("Accept", "image/avif,image/webp,*/*")
                    add("Host", page.imageUrl!!.toHttpUrl().host)
                }.build()

        return GET(page.imageUrl!!, imgHeaders)
    }

    private fun parsePageList(document: Document): List<Page> =
        document.select("section[x-data~=scroll] > img").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }

    private fun fetchAdjacentChapterPages(document: Document): List<Page>? =
        runCatching {
            val chapterUrl = document.location().toHttpUrlOrNull() ?: return@runCatching null
            val chapterIndex = chapterUrl.pathSegments.indexOf("chapters")
            val currentChapterId = chapterUrl.pathSegments.getOrNull(chapterIndex + 1) ?: return@runCatching null
            val navigationDocument =
                client.newCall(GET("$baseUrl/chapters/$currentChapterId", headers)).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@runCatching null
                    }
                    response.asJsoup()
                }
            val adjacentUrl =
                navigationDocument
                    .select("script")
                    .joinToString("\n") { it.data() }
                    .findWeebCentralAdjacentChapterUrl(currentChapterId)
                    ?: return@runCatching null
            val adjacentImagesUrl = "${adjacentUrl.substringBefore("?")}/images?is_prev=False&reading_style=long_strip"

            client.newCall(GET(adjacentImagesUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                parsePageList(response.asJsoup())
            }
        }.getOrNull()

    private fun filterEdgeDuplicatePages(
        pages: List<Page>,
        adjacentPagesProvider: () -> List<Page>?,
    ): List<Page> {
        prefetchedPageImages.clear()
        val identity =
            pages
                .firstNotNullOfOrNull { page -> page.imageUrl?.toWeebCentralPageIdentity() }
                ?: return pages
        val referenceFingerprints =
            duplicatePageHistory
                .previousFingerprints(identity)
                .groupByTo(mutableMapOf()) { it.index }
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.mapTo(mutableListOf()) { it.value } }
        val inspectedPages = mutableMapOf<Int, InspectedEdgePage>()
        val currentFingerprints = linkedMapOf<Int, PageFingerprint>()

        // The neighboring chapter is the strongest reference for newly introduced or
        // shifted scan-group pages, even when older chapter history is already present.
        val adjacentPages = adjacentPagesProvider()
        val adjacentIdentity =
            adjacentPages
                ?.firstNotNullOfOrNull { page -> page.imageUrl?.toWeebCentralPageIdentity() }
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
                (
                    position >= 0 &&
                        inspected.templateSignature?.let { candidate ->
                        inspectedAdjacentPages.values.any { reference ->
                            reference.templateSignature?.let(candidate::isDuplicateOf) == true
                        }
                    } == true
                    ) ||
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
                val inspectLeading = index in leadingScan.inspectedIndices
                val inspectTrailing = index in trailingScan.inspectedIndices
                val edit =
                    inspected.stripProfile?.generalizedSeamEdit(
                        signatures = inspected.edgeRegionSignatures,
                        references = edgeReferences.flatMap(InspectedEdgePage::edgeRegionSignatures),
                        inspectLeading = inspectLeading,
                        inspectTrailing = inspectTrailing,
                    )
                val originalUrl = page.imageUrl ?: return@forEach
                val bounds = edit?.cropBounds(inspected.fingerprint.width, inspected.fingerprint.height)
                val croppedImage = bounds?.let { cropPageImage(inspected.originalImage.bytes, it) }
                val servedUrl =
                    croppedImage?.let { originalUrl.withWeebCentralEdgeCropCacheKey(requireNotNull(bounds)) }
                        ?: originalUrl
                page.imageUrl = servedUrl
                croppedImage?.let { prefetchedPageImages.put(servedUrl, it) }
            }

        return reindexPages(pages, duplicateIndices)
    }

    private fun inspectEdgePage(page: Page): InspectedEdgePage? =
        try {
            client.newCall(imageRequest(page).withFreshWeebCentralImageInspection()).execute().use { response ->
                if (!response.isSuccessful || response.body.contentLength() > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                val body = response.body
                val bytes = body.bytes()
                if (bytes.size > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                val fingerprint = createPageFingerprint(bytes)
                val stripProfile = createEdgeStripProfile(bytes, fingerprint.width, fingerprint.height)
                InspectedEdgePage(
                    fingerprint = fingerprint,
                    originalImage = PrefetchedPageImage(bytes, body.contentType()?.toString()),
                    stripProfile = stripProfile,
                    edgeRegionSignatures =
                    stripProfile?.let { createEdgeRegionSignatures(bytes, it) }.orEmpty(),
                    templateSignature = createWeebCentralTemplateSignature(bytes),
                    forceExclude =
                    isStructurallyInvalidWeebCentralEdgeImage(fingerprint.width, fingerprint.height),
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun createWeebCentralTemplateSignature(bytes: ByteArray): WeebCentralTemplateSignature? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var scaled: Bitmap? = null
        return try {
            if (source.width < source.height || source.width <= 0 || source.height <= 0) return null
            scaled = Bitmap.createScaledBitmap(source, TEMPLATE_SIGNATURE_GRID_SIZE, TEMPLATE_SIGNATURE_GRID_SIZE, true)
            val pixels = IntArray(TEMPLATE_SIGNATURE_GRID_SIZE * TEMPLATE_SIGNATURE_GRID_SIZE)
            scaled.getPixels(pixels, 0, TEMPLATE_SIGNATURE_GRID_SIZE, 0, 0, TEMPLATE_SIGNATURE_GRID_SIZE, TEMPLATE_SIGNATURE_GRID_SIZE)
            WeebCentralTemplateSignature(
                width = source.width,
                height = source.height,
                luma = ByteArray(pixels.size) { index -> (pixels[index].weebCentralLuminance() / 16).toByte() },
            )
        } finally {
            if (scaled !== source) scaled?.recycle()
            source.recycle()
        }
    }

    private fun createEdgeStripProfile(
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): WeebCentralEdgeStripProfile? {
        if (width <= 0 || height <= 0) return null
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
            WeebCentralEdgeStripProfile(width, height, scaledHeight, luma)
        } finally {
            if (scaled !== source) scaled?.recycle()
            source.recycle()
        }
    }

    private fun createEdgeRegionSignatures(
        bytes: ByteArray,
        profile: WeebCentralEdgeStripProfile,
    ): List<WeebCentralEdgeRegionSignature> {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        return try {
            buildList {
                listOf(true, false).forEach { fromTop ->
                    profile.strongEdgeBoundaryRows(fromTop).forEach { edgeRows ->
                        val approximateHeight = profile.normalizedRowsToPixels(edgeRows)
                        val pixelHeight = source.refineEdgeBoundary(approximateHeight, fromTop)
                        val top = if (fromTop) 0 else source.height - pixelHeight
                        source.blockAverageHash(top, pixelHeight)?.let { hash ->
                            add(
                                WeebCentralEdgeRegionSignature(
                                    edgeRows,
                                    pixelHeight,
                                    fromTop,
                                    hash,
                                    profile.edgeBoundaryMeanDifference(edgeRows, fromTop),
                                ),
                            )
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
        bounds: WeebCentralCropBounds,
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

    // ============================= Utilities ==============================

    private fun Element.sourceImg(): String? =
        selectFirst("source")?.attr("srcset")?.replace("small", "normal")
            ?: selectFirst("img")?.absUrl("src")

    private fun defaultFilterList(sortFilter: SortFilter): FilterList =
        FilterList(
            sortFilter,
            SortOrderFilter(),
            OfficialTranslationFilter(),
            StatusFilter(),
            TypeFilter(),
            TagFilter(),
        )

    companion object {
        // The related "&limit=" query parameter of the api is currently non functional
        // and always returns 32 entries per request
        const val FETCH_LIMIT = 32
        const val URL_SEARCH_PREFIX = "id:"

        private const val DUPLICATE_PAGE_HISTORY_NAMESPACE = "weebcentral.leading_pages.v1"
        private const val MAX_FINGERPRINT_IMAGE_BYTES = 16 * 1024 * 1024
        private const val EDGE_STRIP_PROFILE_WIDTH = 32
    }
}

private data class InspectedEdgePage(
    val fingerprint: PageFingerprint,
    val originalImage: PrefetchedPageImage,
    val stripProfile: WeebCentralEdgeStripProfile?,
    val edgeRegionSignatures: List<WeebCentralEdgeRegionSignature>,
    val templateSignature: WeebCentralTemplateSignature?,
    val forceExclude: Boolean,
)

internal data class WeebCentralTemplateSignature(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    fun isDuplicateOf(other: WeebCentralTemplateSignature): Boolean {
        if (
            width < height || other.width < other.height ||
            width != other.width || height != other.height ||
            luma.size != TEMPLATE_SIGNATURE_GRID_SIZE * TEMPLATE_SIGNATURE_GRID_SIZE ||
            other.luma.size != luma.size
        ) {
            return false
        }

        var difference = 0
        var samples = 0
        for (index in luma.indices) {
            val row = index / TEMPLATE_SIGNATURE_GRID_SIZE
            val column = index % TEMPLATE_SIGNATURE_GRID_SIZE
            if (
                row >= TEMPLATE_SIGNATURE_PERIMETER &&
                row < TEMPLATE_SIGNATURE_GRID_SIZE - TEMPLATE_SIGNATURE_PERIMETER &&
                column >= TEMPLATE_SIGNATURE_PERIMETER &&
                column < TEMPLATE_SIGNATURE_GRID_SIZE - TEMPLATE_SIGNATURE_PERIMETER
            ) {
                continue
            }
            difference += kotlin.math.abs((luma[index].toInt() and 0xFF) - (other.luma[index].toInt() and 0xFF))
            samples++
        }
        return difference <= samples * MAXIMUM_TEMPLATE_MEAN_LUMA_DIFFERENCE
    }
}

internal fun Request.withFreshWeebCentralImageInspection(): Request =
    newBuilder()
        .url(
            url.newBuilder()
                .setQueryParameter(WEEBCENTRAL_EDGE_INSPECTION_QUERY_PARAMETER, "v1")
                .build(),
        )
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()

internal data class WeebCentralEdgeStripProfile(
    val originalWidth: Int,
    val originalHeight: Int,
    val heightRows: Int,
    val luma: ByteArray,
) {
    val profileWidth: Int
        get() = if (heightRows > 0) luma.size / heightRows else 0
}

internal data class WeebCentralEdgeRegionSignature(
    val edgeRows: Int,
    val edgePixels: Int,
    val fromTop: Boolean,
    val hash: ByteArray,
    val seamMeanDifference: Int = Int.MAX_VALUE,
) {
    fun isDuplicateOf(other: WeebCentralEdgeRegionSignature): Boolean {
        if (
            kotlin.math.abs(edgeRows - other.edgeRows) > MAXIMUM_EDGE_SIGNATURE_ROW_DELTA ||
            hash.size != other.hash.size
        ) {
            return false
        }
        val maximumDistance =
            if (minOf(seamMeanDifference, other.seamMeanDifference) >= MINIMUM_STRONG_EDGE_SEAM_MEAN_DIFFERENCE) {
                MAXIMUM_EDGE_SIGNATURE_DISTANCE
            } else {
                MAXIMUM_WEAK_EDGE_SIGNATURE_DISTANCE
            }
        var directDistance = 0
        var invertedDistance = 0
        for (index in hash.indices) {
            if (hash[index] != other.hash[index]) directDistance++
            if (hash[index] == other.hash[index]) invertedDistance++
            if (directDistance > maximumDistance && invertedDistance > maximumDistance) {
                return false
            }
        }
        return minOf(directDistance, invertedDistance) <= maximumDistance
    }
}

internal fun isStructurallyInvalidWeebCentralEdgeImage(
    width: Int,
    height: Int,
): Boolean = width <= 1 || height <= 1

internal fun WeebCentralEdgeStripProfile.generalizedSeamEdit(
    signatures: List<WeebCentralEdgeRegionSignature>,
    references: List<WeebCentralEdgeRegionSignature>,
    inspectLeading: Boolean,
    inspectTrailing: Boolean,
): WeebCentralEdgePageEdit? {
    fun matchingPixels(fromTop: Boolean): Int =
        signatures
            .asSequence()
            .filter { it.fromTop == fromTop }
            .filter { candidate -> references.any(candidate::isDuplicateOf) }
            .maxOfOrNull(WeebCentralEdgeRegionSignature::edgePixels)
            ?: 0

    var topPixels = if (inspectLeading) matchingPixels(fromTop = true) else 0
    var bottomPixels = if (inspectTrailing) matchingPixels(fromTop = false) else 0
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
    return WeebCentralEdgePageEdit(
        topOffset = topPixels,
        retainedHeight = bottomPixels.takeIf { it > 0 }?.let { originalHeight - topPixels - it },
    )
}

internal fun WeebCentralEdgeStripProfile.strongEdgeBoundaryRows(fromTop: Boolean): List<Int> {
    val maximumRows =
        minOf(
            profileWidth * MAXIMUM_EDGE_WIDTH_MULTIPLIER,
            heightRows * MAXIMUM_EDGE_CROP_NUMERATOR / MAXIMUM_EDGE_CROP_DENOMINATOR,
        )
    if (maximumRows < MINIMUM_REPEATED_EDGE_ROWS) return emptyList()
    return (MINIMUM_REPEATED_EDGE_ROWS..maximumRows).filter { edgeRows ->
        val boundaryRow = if (fromTop) edgeRows else heightRows - edgeRows
        val startRow = if (fromTop) 0 else heightRows - edgeRows
        boundaryRow > 0 &&
            boundaryRow < heightRows &&
            hasVisualDetail(startRow, edgeRows) &&
            horizontalRowDifference(boundaryRow - 1, boundaryRow) >=
                MINIMUM_EDGE_SEAM_MEAN_DIFFERENCE * profileWidth
    }
}

private fun WeebCentralEdgeStripProfile.edgeBoundaryMeanDifference(
    edgeRows: Int,
    fromTop: Boolean,
): Int {
    val boundaryRow = if (fromTop) edgeRows else heightRows - edgeRows
    if (profileWidth <= 0 || boundaryRow <= 0 || boundaryRow >= heightRows) return 0
    return (horizontalRowDifference(boundaryRow - 1, boundaryRow) / profileWidth).toInt()
}

private fun WeebCentralEdgeStripProfile.hasVisualDetail(
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

private fun WeebCentralEdgeStripProfile.horizontalRowDifference(
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

internal fun WeebCentralEdgeStripProfile.normalizedRowsToPixels(rows: Int): Int =
    if (rows <= 0) 0 else (rows * originalWidth + profileWidth - 1) / profileWidth

private fun Bitmap.refineEdgeBoundary(
    approximateHeight: Int,
    fromTop: Boolean,
): Int {
    val radius = maxOf(4, width / 32)
    val minimum = maxOf(1, approximateHeight - radius)
    val maximum = minOf(height - 1, approximateHeight + radius)
    var bestHeight = approximateHeight.coerceIn(minimum, maximum)
    var bestDifference = -1L
    val sampleStep = maxOf(1, width / 256)
    for (edgePixels in minimum..maximum) {
        val boundaryY = if (fromTop) edgePixels else height - edgePixels
        if (boundaryY <= 0 || boundaryY >= height) continue
        var difference = 0L
        for (x in 0 until width step sampleStep) {
            val first = getPixel(x, boundaryY - 1).weebCentralLuminance()
            val second = getPixel(x, boundaryY).weebCentralLuminance()
            difference += kotlin.math.abs(first - second)
        }
        if (difference > bestDifference) {
            bestDifference = difference
            bestHeight = edgePixels
        }
    }
    return bestHeight
}

private fun Int.weebCentralLuminance(): Int =
    (
        (this shr 16 and 0xFF) * 299 +
            (this shr 8 and 0xFF) * 587 +
            (this and 0xFF) * 114
        ) / 1000
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

internal data class WeebCentralCropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class WeebCentralEdgePageEdit(
    val topOffset: Int = 0,
    val retainedHeight: Int? = null,
) {
    fun cropBounds(
        imageWidth: Int,
        imageHeight: Int,
    ): WeebCentralCropBounds? {
        if (topOffset == 0 && retainedHeight == null) return null
        val height = retainedHeight ?: (imageHeight - topOffset)
        return WeebCentralCropBounds(0, topOffset, imageWidth, height)
            .takeIf {
                imageWidth > 0 &&
                    topOffset >= 0 &&
                    height > 0 &&
                    topOffset + height <= imageHeight
            }
    }
}

internal fun String.withWeebCentralEdgeCropCacheKey(bounds: WeebCentralCropBounds): String =
    toHttpUrlOrNull()
        ?.newBuilder()
        ?.setQueryParameter(
            WEEBCENTRAL_EDGE_CROP_QUERY_PARAMETER,
            "v4-${bounds.x}-${bounds.y}-${bounds.width}-${bounds.height}",
        )
        ?.build()
        ?.toString()
        ?: this

private const val EDGE_SIGNATURE_GRID_SIZE = 16
private const val TEMPLATE_SIGNATURE_GRID_SIZE = 32
private const val TEMPLATE_SIGNATURE_PERIMETER = 8
private const val MAXIMUM_TEMPLATE_MEAN_LUMA_DIFFERENCE = 2
private const val MAXIMUM_EDGE_SIGNATURE_DISTANCE = 72
private const val MAXIMUM_WEAK_EDGE_SIGNATURE_DISTANCE = 24
private const val MAXIMUM_EDGE_SIGNATURE_ROW_DELTA = 4
private const val MINIMUM_EDGE_SEAM_MEAN_DIFFERENCE = 12L
private const val MINIMUM_STRONG_EDGE_SEAM_MEAN_DIFFERENCE = 24
private const val MINIMUM_REPEATED_EDGE_ROWS = 8
private const val MINIMUM_EDGE_LUMA_RANGE = 48
private const val MAXIMUM_EDGE_WIDTH_MULTIPLIER = 2
private const val MAXIMUM_EDGE_CROP_NUMERATOR = 2
private const val MAXIMUM_EDGE_CROP_DENOMINATOR = 3
private const val MINIMUM_RETAINED_EDGE_PIXELS = 256
private const val WEEBCENTRAL_EDGE_INSPECTION_QUERY_PARAMETER = "tachiyomi_edge_inspection"
private const val WEEBCENTRAL_EDGE_CROP_QUERY_PARAMETER = "tachiyomi_edge_crop"
private val WEEBCENTRAL_ADJACENT_CHAPTER_REGEX =
    """window\.location\.href\s*=\s*"(https://weebcentral\.com/chapters/([0-9A-Z]+)(?:\?is_prev=True)?)""""
        .toRegex()

internal fun String.findWeebCentralAdjacentChapterUrl(currentChapterId: String): String? =
    WEEBCENTRAL_ADJACENT_CHAPTER_REGEX
        .findAll(this)
        .firstOrNull { match -> match.groupValues[2] != currentChapterId }
        ?.groupValues
        ?.get(1)

internal fun String.toWeebCentralPageIdentity(): LeadingPageIdentity? {
    val pathSegments = toHttpUrlOrNull()?.pathSegments ?: return null
    val mangaIndex = pathSegments.indexOfLast { it.equals("manga", ignoreCase = true) }
    val seriesKey = pathSegments.getOrNull(mangaIndex + 1)?.takeIf(String::isNotBlank) ?: return null
    val fileName = pathSegments.lastOrNull()?.takeIf(String::isNotBlank) ?: return null
    val chapterKey = fileName.substringBeforeLast("-").takeIf { it != fileName && it.isNotBlank() } ?: return null
    return LeadingPageIdentity(seriesKey, chapterKey)
}

private val OFFICIAL_PREFIX_REGEX =
    """^[\s\p{Z}\u200B\u2060\uFEFF]*Official[\s\p{Z}\u200B\u2060\uFEFF]+"""
        .toRegex(RegexOption.IGNORE_CASE)

internal fun String.withoutWeebCentralOfficialPrefix(): String = OFFICIAL_PREFIX_REGEX.replaceFirst(this, "")

internal const val WEEBCENTRAL_LIST_TITLE_SELECTOR = "div:not([class]):last-child"

internal fun Element.weebCentralTitle(selector: String): String =
    selectFirst(selector)!!.text().withoutWeebCentralOfficialPrefix()

internal fun Document.selectWeebCentralMangaElements(itemSelector: String): List<Element> =
    select(itemSelector)
