package com.xlythe.tachiyomi.extension.en.mangabat

import android.content.SharedPreferences
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
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

        val adjacentPages =
            if (referenceFingerprints.isEmpty()) {
                adjacentPagesProvider()
            } else {
                null
            }
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
    val image: PrefetchedPageImage,
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
