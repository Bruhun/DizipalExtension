package com.example.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dizipal2084Provider : MainAPI() {
    override var mainUrl = "https://dizipal2084.com"
    override var name = "Dizipal 2084"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ================= 1. HOME PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val trendingItems = document.select("a.trending-item").mapNotNull { parseTrendingCard(it) }
        if (trendingItems.isNotEmpty()) {
            homePageList.add(HomePageList("Trendler", trendingItems))
        }

        val latestItems = document.select(".content-card a.card-link").mapNotNull { parseContentCard(it) }
        if (latestItems.isNotEmpty()) {
            homePageList.add(HomePageList("Son Eklenenler", latestItems))
        }

        if (homePageList.isEmpty()) throw ErrorLoadingException("Ana sayfa y\u00fcklenemedi")
        return newHomePageResponse(homePageList, false)
    }

    private fun parseTrendingCard(element: Element): SearchResponse? {
        val url = fixUrlNull(element.attr("href").takeIf { it.isNotBlank() } ?: return null) ?: return null
        val title = element.selectFirst(".trending-title")?.text()?.trim() ?: return null
        val badge = element.selectFirst(".trending-badge")?.text()?.trim()?.lowercase() ?: ""
        val poster = element.selectFirst("img")?.let { DizipalUtils.extractImage(it, mainUrl) }
        val type = if (badge.contains("film")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun parseContentCard(element: Element): SearchResponse? {
        val url = fixUrlNull(element.attr("href").takeIf { it.isNotBlank() } ?: return null) ?: return null
        val title = element.selectFirst(".card-title")?.text()?.trim() ?: return null
        val badge = element.selectFirst(".card-badge")?.text()?.trim()?.lowercase() ?: ""
        val poster = element.selectFirst("img")?.let { DizipalUtils.extractImage(it, mainUrl) }
        val type = if (badge.contains("film") || url.contains("/film/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // ================= 2. SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery"
        val document = app.get(searchUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").document
        return document.select(".content-card a.card-link").mapNotNull { parseContentCard(it) }
    }

    // ================= 3. DETAIL PAGE =================
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)

        return when {
            "/dizi/" in fixedUrl -> loadSeriesPage(fixedUrl)
            "/film/" in fixedUrl -> loadMoviePage(fixedUrl)
            else -> {
                val document = app.get(fixedUrl, headers = DizipalUtils.headers, referer = "$mainUrl/").document
                if (document.selectFirst(".season-buttons") != null) {
                    loadSeriesPage(fixedUrl, document)
                } else {
                    loadMoviePage(fixedUrl, document)
                }
            }
        }
    }

    private suspend fun loadSeriesPage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DizipalUtils.headers, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1.page-title, h1.series-title, meta[property=\"og:title\"]")?.let {
            if (it.tagName() == "meta") it.attr("content").replace(" izle | Dizipal", "").trim()
            else it.text().trim()
        } ?: throw ErrorLoadingException("Ba\u015fl\u0131k bulunamad\u0131")

        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst(".series-poster img, .detail-poster img")?.let { DizipalUtils.extractImage(it, mainUrl) }

        val description = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            ?: doc.selectFirst(".series-description, .detail-description")?.text()?.trim()

        val year = doc.selectFirst("meta[property=\"og:video:release_date\"]")?.attr("content")?.take(4)?.toIntOrNull()

        val episodeList = ArrayList<Episode>()

        doc.select(".detail-episode-list, .season-content").forEach { seasonBlock ->
            val rawSeason = seasonBlock.attr("data-episodes").ifBlank {
                seasonBlock.attr("data-season-content")
            }
            val season = rawSeason.toIntOrNull() ?: return@forEach

            seasonBlock.select("a.detail-episode-item, a.episode-item").forEach { epEl ->
                val href = epEl.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val epUrl = fixUrl(href)
                val epLabel = epEl.selectFirst(".detail-episode-title, .ep-label")?.text()?.trim()
                    ?: epEl.selectFirst(".detail-episode-subtitle")?.text()?.trim()
                    ?: "$season. Sezon"

                episodeList.add(
                    newEpisode(epUrl) {
                        this.name = epLabel
                        this.season = season
                    }
                )
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

    private suspend fun loadMoviePage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DizipalUtils.headers, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1.page-title, meta[property=\"og:title\"]")?.let {
            if (it.tagName() == "meta") it.attr("content").replace(" izle | Dizipal", "").trim()
            else it.text().trim()
        } ?: throw ErrorLoadingException("Film ba\u015fl\u0131\u011f\u0131 bulunamad\u0131")

        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val description = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()

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

        val slug = fixedData.substringAfter("$mainUrl/bolum/")
            .substringAfter("/bolum/")
            .substringAfter("$mainUrl/film/")
            .substringAfter("/film/")
            .trim('/')

        if (slug.isBlank()) return false

        val type = if ("/film/" in fixedData) "film" else "dizi"
        val embedUrl = "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=$mainUrl&type=$type"

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
}
