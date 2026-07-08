package com.example.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // ================= 1. HOME PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers, referer = "$mainUrl/").document
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

        val poster = extractImage(element.selectFirst("img"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ================= 2. SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery&ajax=1"
        val response = app.get(searchUrl, headers = ajaxHeaders, referer = "$mainUrl/")

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
                val document = app.get(fixedUrl, headers = headers, referer = "$mainUrl/").document
                if (document.selectFirst(".dp-season-btns") != null) {
                    loadSeriesPage(fixedUrl, document)
                } else {
                    loadMoviePage(fixedUrl, document)
                }
            }
        }
    }

    private suspend fun loadSeriesPage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = headers, referer = "$mainUrl/").document
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

        doc.select(".dp-season-btn").forEach { seasonBtn ->
            val seasonNumber = seasonBtn.attr("data-season").toIntOrNull()
                ?: seasonBtn.text().filter { it.isDigit() }.toIntOrNull()
                ?: return@forEach

            val episodes: List<EpisodeJson> = try {
                val epResponse = app.get(
                    "$mainUrl/bolum_yukle.php?slug=${URLEncoder.encode(slug, "UTF-8")}&sezon=$seasonNumber",
                    headers = ajaxHeaders,
                    referer = url
                )
                mapper.readValue(epResponse.text)
            } catch (_: Exception) {
                emptyList<EpisodeJson>()
            }

            episodes.forEachIndexed { index, ep ->
                episodeList.add(
                    newEpisode(fixUrl("/bolum/${ep.slug}")) {
                        this.name = ep.baslik ?: "Sezon $seasonNumber B\u00f6l\u00fcm ${index + 1}"
                        this.season = seasonNumber
                        this.episode = index + 1
                    }
                )
            }
        }

        if (episodeList.isEmpty()) {
            doc.select(".dp-hero-btns a[href*=/bolum/]").forEachIndexed { index, btn ->
                val epUrl = fixUrl(btn.attr("href"))
                episodeList.add(
                    newEpisode(epUrl) {
                        this.name = btn.text().trim()
                        this.season = 1
                        this.episode = index + 1
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun loadMoviePage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.fp-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Film ba\u015fl\u0131\u011f\u0131 bulunamad\u0131")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".fp-poster img")?.let { extractImage(it) }

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
                val doc = app.get(fixedData, headers = headers, referer = "$mainUrl/").document
                val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) return resolveEmbed(fixUrl(iframeSrc), callback)
                return false
            }
        }

        return resolveEmbed(embedUrl, callback)
    }

    private suspend fun resolveEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(embedUrl, headers = headers, referer = "$mainUrl/").text
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

    // ================= HELPERS =================
    private fun extractImage(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return when {
            src.isBlank() -> null
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> mainUrl + src
        }
    }

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
