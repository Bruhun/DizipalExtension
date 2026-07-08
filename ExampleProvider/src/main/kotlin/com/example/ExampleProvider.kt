package com.example.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal2096.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val mapper = jacksonObjectMapper()

    // ================= 1. HOME PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").document
        val latestEpisodes = document.select(".ip-ep-card").mapNotNull { parseEpisodeCard(it) }
        if (latestEpisodes.isEmpty()) throw ErrorLoadingException("Ana sayfa y\u00fcklenemedi")
        return newHomePageResponse(HomePageList("Son B\u00f6l\u00fcmler", latestEpisodes), false)
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href").takeIf { it.isNotBlank() }
            ?: element.selectFirst("a")?.attr("href")
            ?: return null) ?: return null

        val title = element.selectFirst(".ip-ep-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = DizipalUtils.extractImage(element.selectFirst("img"), mainUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ================= 2. SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery&ajax=1"
        val response = app.get(searchUrl, headers = DizipalUtils.ajaxHeaders, referer = "$mainUrl/")

        val results: List<SearchJson> = try {
            mapper.readValue(response.text)
        } catch (e: Exception) {
            throw ErrorLoadingException("Arama sonu\u00e7lar\u0131 ayr\u0131\u015ft\u0131r\u0131lamad\u0131: ${e.message}")
        }

        if (results.isEmpty()) return emptyList()

        return results.mapNotNull { item ->
            val url = fixUrl("/${item.type}/${item.slug}")
            val poster = item.poster?.takeIf { it.isNotBlank() }
            when (item.type) {
                "film" -> newMovieSearchResponse(item.title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
                "dizi", "anime" -> newTvSeriesSearchResponse(item.title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
                else -> null
            }
        }
    }

    // ================= 3. DETAIL PAGE =================
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)

        return when {
            "/dizi/" in fixedUrl -> loadSeriesPage(fixedUrl)
            "/film/" in fixedUrl -> loadMoviePage(fixedUrl)
            else -> {
                val document = app.get(fixedUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").document
                if (document.selectFirst(".dp-season-btns") != null) {
                    loadSeriesPage(fixedUrl, document)
                } else {
                    loadMoviePage(fixedUrl, document)
                }
            }
        }
    }

    private suspend fun loadSeriesPage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DizipalUtils.headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.dp-series-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Dizi ba\u015fl\u0131\u011f\u0131 bulunamad\u0131")

        val poster = doc.selectFirst(".dp-hero")?.attr("style")
            ?.let { Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("p.dp-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = doc.selectFirst(".dp-info-val")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.selectFirst("meta[property=og:video:release_date]")?.attr("content")?.take(4)?.toIntOrNull()

        val episodeList = ArrayList<Episode>()
        val slug = url.substringAfter("$mainUrl/dizi/").substringAfter("/dizi/").trim('/')
        val seasonNumbers = doc.select(".dp-season-btn").mapNotNull { btn ->
            btn.attr("data-season").toIntOrNull()
        }.toMutableList()
        if (seasonNumbers.isEmpty()) {
            seasonNumbers.add(1)
        }

        val allSeasons = seasonNumbers.toMutableSet()

        try {
            val s1e1Url = fixUrl("/bolum/$slug-1-sezon-1-bolum")
            val s1e1Doc = app.get(s1e1Url, headers = DizipalUtils.headers, referer = url).document
            s1e1Doc.select(".bp-stab").mapNotNull { tab ->
                tab.attr("data-season").toIntOrNull()
            }.forEach { allSeasons.add(it) }
        } catch (_: Exception) {
        }

        val sortedSeasons = allSeasons.sorted()

        coroutineScope {
            sortedSeasons.chunked(2).forEach { batch ->
                batch.map { season ->
                    async {
                        discoverSeasonEpisodes(season, slug, url)
                    }
                }.awaitAll().forEach { eps -> episodeList.addAll(eps) }
            }
        }

        if (episodeList.isEmpty()) {
            throw ErrorLoadingException("Hi\u00e7 b\u00f6l\u00fcm bulunamad\u0131")
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun discoverSeasonEpisodes(season: Int, slug: String, referer: String): List<Episode> {
        val episodes = ArrayList<Episode>()
        var episodeNum = 1
        var consecutiveFails = 0
        while (consecutiveFails < 5 && episodeNum <= 30) {
            delay(250L)
            val epUrl = fixUrl("/bolum/$slug-$season-sezon-$episodeNum-bolum")
            val epTitle = fetchEpisodeTitle(epUrl, referer)
            if (!epTitle.isNullOrBlank()) {
                consecutiveFails = 0
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episodeNum
                    }
                )
            } else {
                consecutiveFails++
            }
            episodeNum++
        }
        return episodes
    }

    private suspend fun fetchEpisodeTitle(epUrl: String, referer: String): String? {
        suspend fun attempt(): String? {
            val epDoc = app.get(epUrl, headers = DizipalUtils.headers, referer = referer).document
            return epDoc.selectFirst("h1.bp-ep-title, h1.dp-series-title")?.text()?.trim()
        }
        return try { attempt() } catch (_: Exception) {
            delay(1000L)
            try { attempt() } catch (_: Exception) { null }
        }
    }

    private suspend fun loadMoviePage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DizipalUtils.headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.fp-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Film ba\u015fl\u0131\u011f\u0131 bulunamad\u0131")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".fp-poster img")?.let { DizipalUtils.extractImage(it, mainUrl) }

        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ================= 4. VIDEO LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixUrl(data)

        val embedUrl = when {
            "/film/" in fixedData -> {
                val slug = fixedData.substringAfter("$mainUrl/film/").substringAfter("/film/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=$mainUrl&type=film"
            }
            "/bolum/" in fixedData -> {
                val slug = fixedData.substringAfter("$mainUrl/bolum/").substringAfter("/bolum/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=$mainUrl&type=dizi"
            }
            else -> {
                val doc = app.get(fixedData, headers = DizipalUtils.headers, referer = "$mainUrl/").document
                val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) return resolveEmbed(fixUrl(iframeSrc), callback)
                return false
            }
        }

        return resolveEmbed(embedUrl, callback)
    }

    private suspend fun resolveEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(embedUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").text
            val hlsUrl = Regex("""var src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (!hlsUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // ================= DATA CLASSES =================
    data class SearchJson(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null
    )

    data class EpisodeJson(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("baslik") val baslik: String? = null,
        @JsonProperty("alt") val alt: String? = null
    )
}
