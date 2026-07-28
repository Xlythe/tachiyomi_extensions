package com.xlythe.tachiyomi.extension.en.weebcentral

import android.content.SharedPreferences
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class WeebCentral : ParsedHttpSource() {
    override val name = "Weeb Central"

    override val baseUrl = "https://weebcentral.com"

    override val lang = "en"

    override val supportsLatest = true

    private val prefetchedPageImages = PrefetchedPageImageStore()

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

        val adjacentPages =
            if (referenceFingerprints.isEmpty()) {
                adjacentPagesProvider()
            } else {
                null
            }
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
            return referenceFingerprints.hasDuplicateFingerprintAtEdge(position, inspected.fingerprint)
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
                page.imageUrl?.let { prefetchedPageImages.put(it, inspected.image) }
            }

        return reindexPages(pages, duplicateIndices)
    }

    private fun inspectEdgePage(page: Page): InspectedEdgePage? =
        try {
            client.newCall(imageRequest(page)).execute().use { response ->
                if (!response.isSuccessful || response.body.contentLength() > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                val body = response.body
                val bytes = body.bytes()
                if (bytes.size > MAX_FINGERPRINT_IMAGE_BYTES) {
                    return null
                }

                InspectedEdgePage(
                    fingerprint = createPageFingerprint(bytes),
                    image = PrefetchedPageImage(bytes, body.contentType()?.toString()),
                )
            }
        } catch (_: Exception) {
            null
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
    }
}

private data class InspectedEdgePage(
    val fingerprint: PageFingerprint,
    val image: PrefetchedPageImage,
)

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
