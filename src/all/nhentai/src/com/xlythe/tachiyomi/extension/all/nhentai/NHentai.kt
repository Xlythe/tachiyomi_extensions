package com.xlythe.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.xlythe.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import com.xlythe.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import com.xlythe.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import com.xlythe.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : ParsedHttpSource(),
    ConfigurableSource {
    final override val baseUrl = "https://nhentai.net"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient
            .newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            ).rateLimit(4)
            .build()
    }

    private var displayFullTitle: Boolean =
        when (preferences.getString(TITLE_PREF, "full")) {
            "full" -> true
            else -> false
        }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")

    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = TITLE_PREF
                title = TITLE_PREF
                entries = arrayOf("Full Title", "Short Title")
                entryValues = arrayOf("full", "short")
                summary = "%s"
                setDefaultValue("full")

                setOnPreferenceChangeListener { _, newValue ->
                    displayFullTitle =
                        when (newValue) {
                            "full" -> true
                            else -> false
                        }
                    true
                }
            }.also(screen::addPreference)

        addRandomUAPreferenceToScreen(screen)
    }

    override fun latestUpdatesRequest(page: Int) =
        GET(if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page", headers)

    override fun latestUpdatesSelector() = "#content .container:not(.index-popular) .gallery"

    override fun latestUpdatesFromElement(element: Element) =
        SManga.create().apply {
            setUrlWithoutDomain(element.select("a").attr("href"))
            title =
                element.select("a > div").text().replace("\"", "").let {
                    if (displayFullTitle) it.trim() else it.shortenTitle()
                }
            thumbnail_url =
                element.selectFirst(".cover img")!!.let { img ->
                    if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
                }
        }

    override fun latestUpdatesNextPageSelector() = NHENTAI_NEXT_PAGE_SELECTOR

    override fun popularMangaRequest(page: Int) =
        GET(
            if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page" else "$baseUrl/language/$nhLang/popular?page=$page",
            headers,
        )

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> =
        when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val id = query.removePrefix(PREFIX_ID_SEARCH)
                client
                    .newCall(searchMangaByIdRequest(id))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, id) }
            }
            query.toIntOrNull() != null -> {
                client
                    .newCall(searchMangaByIdRequest(query))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, query) }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val tags = filterList.findInstance<TagFilter>()?.state.orEmpty()
        val sort = filterList.findInstance<SortFilter>()?.toUriPart()

        return GET(
            buildNHentaiSearchUrl(
                baseUrl = baseUrl,
                page = page,
                query = query,
                language = nhLang,
                tags = tags,
                sort = sort,
            ),
            headers,
        )
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(
        response: Response,
        id: String,
    ): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url
            .toString()
            .contains("/login/")
        ) {
            val document = response.asJsoup()
            if (document.select(".fa-sign-in").isNotEmpty()) {
                throw Exception("Log in via WebView to view favorites")
            }
        }

        return super.searchMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val data = document.getHentaiData(json)
        return SManga.create().apply {
            title =
                if (displayFullTitle) {
                    data.title.english ?: data.title.japanese ?: data.title.pretty!!
                } else {
                    data.title.pretty
                        ?: (data.title.english ?: data.title.japanese)!!.shortenTitle()
                }
            thumbnail_url = data.thumbnailUrl()
            status = SManga.COMPLETED
            artist = getArtists(data)
            author = getGroups(data) ?: getArtists(data)
            // Some people want these additional details in description
            description =
                "Full English and Japanese titles:\n"
                    .plus("${data.title.english ?: data.title.japanese ?: data.title.pretty ?: ""}\n")
                    .plus(data.title.japanese ?: "")
                    .plus("\n\n")
                    .plus("Pages: ${data.pages.size}\n")
                    .plus("Favorited by: ${data.num_favorites}\n")
                    .plus(getTagDescription(data))
            genre = getTags(data)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.asJsoup().getHentaiData(json)
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = getGroups(data)
                date_upload = data.upload_date * 1000
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun pageListParse(document: Document): List<Page> {
        val data = document.getHentaiData(json)

        return data.pageImageUrls().mapIndexed { i, imageUrl ->
            Page(
                index = i,
                imageUrl = imageUrl,
            )
        }
    }

    override fun getFilterList(): FilterList =
        FilterList(
            TagFilter(),
            Filter.Separator(),
            SortFilter(),
        )

    class TagFilter : AdvSearchEntryFilter("Tags")

    open class AdvSearchEntryFilter(
        name: String,
    ) : Filter.Text(name)

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}

internal fun Document.getHentaiData(json: Json): Hentai {
    val script =
        selectFirst("""script[data-url^="/api/v2/galleries/"]""")?.data()
            ?: throw Exception("nHentai SvelteKit data not found")
    val responseJson = json.parseToJsonElement(script).jsonObject
    val body =
        responseJson["body"]?.jsonPrimitive?.content
            ?: throw Exception("Body not found in nHentai SvelteKit metadata")
    return json.decodeFromString(body)
}

internal fun Hentai.thumbnailUrl(): String {
    val path =
        thumbnail?.path
            ?: pages.firstOrNull()?.thumbnail?.takeIf { it.isNotBlank() }
            ?: "galleries/$media_id/1t.webp"
    return "https://t1.nhentai.net/${path.trimStart('/')}"
}

internal fun Hentai.pageImageUrls(): List<String> = pages.map { "https://i1.nhentai.net/${it.path.trimStart('/')}" }

internal const val NHENTAI_NEXT_PAGE_SELECTOR = "#content section.pagination a.next"

internal fun buildNHentaiSearchUrl(
    baseUrl: String,
    page: Int,
    query: String,
    language: String,
    tags: String,
    sort: String?,
) = "$baseUrl/search"
    .toHttpUrl()
    .newBuilder()
    // Blank query (Multi + sort by popular month/week/day) shows a 404 page.
    // Searching for `""` returns everything without filtering.
    .addQueryParameter(
        "q",
        listOf(
            query.trim(),
            language.takeIf(String::isNotBlank)?.let { "language:$it" }.orEmpty(),
            buildNHentaiTagQuery(tags),
        ).filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "\"\"" },
    ).addQueryParameter("page", page.toString())
    .apply {
        sort?.let { addQueryParameter("sort", it) }
    }.build()

internal fun buildNHentaiTagQuery(tags: String): String =
    parseNHentaiTags(tags).joinToString(" ") { (excluded, tag) ->
        "${if (excluded) "-" else ""}tag:\"$tag\""
    }

private fun parseNHentaiTags(tags: String): List<Pair<Boolean, String>> {
    val entries =
        if (',' in tags) {
            tags.split(',')
        } else {
            Regex("""-?"[^"]+"|[^\s]+""").findAll(tags).map(MatchResult::value).toList()
        }

    return entries
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull { rawTag ->
            val excluded = rawTag.startsWith("-")
            val tag =
                rawTag
                    .removePrefix("-")
                    .trim()
                    .removeSurrounding("\"")
                    .replace("\"", "")
                    .trim()
            tag.takeIf(String::isNotBlank)?.let { excluded to it }
        }.distinct()
}
