package com.example.plugin

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

    // Cloudstream HTTP headers (NiceHttp)
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

    // ================= 1. ANA SAYFA =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers).document
        val homePageList = ArrayList<HomePageList>()

        // "Son Bölümler" bölümünü çözümle
        val latestEpisodes = ArrayList<SearchResponse>()
        document.select(".ip-ep-card").forEach { element ->
            parseEpisodeCard(element)?.let { latestEpisodes.add(it) }
        }
        if (latestEpisodes.isNotEmpty()) {
            homePageList.add(HomePageList("Son Bölümler", latestEpisodes))
        }

        return newHomePageResponse(homePageList, false)
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() }
            ?: element.selectFirst("a")?.attr("href")
            ?: return null

        val url = fixUrl(href)
        val title = element.selectFirst(".ip-ep-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val imgTag = element.selectFirst("img")
        val poster = extractImage(imgTag)

        val sub = element.selectFirst(".ip-ep-sub")?.text()?.trim() ?: ""
        val isSeries = sub.contains("Sezon", ignoreCase = true) || sub.contains("Bölüm", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ================= 2. ARAMA (JSON API) =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery&ajax=1"
        val response = app.get(searchUrl, headers = ajaxHeaders, referer = "$mainUrl/")

        return try {
            val results: List<SearchJson> = mapper.readValue(response.text)
            results.mapNotNull { item ->
                val url = "$mainUrl/${item.type}/${item.slug}"
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ================= 3. DETAY SAYFASI =================
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)

        // Bölüm linkine tıklanmışsa bölüm sayfasını yükle
        return when {
            "/bolum/" in fixedUrl -> loadEpisodePage(fixedUrl)
            "/dizi/" in fixedUrl -> loadSeriesPage(fixedUrl)
            "/film/" in fixedUrl -> loadMoviePage(fixedUrl)
            else -> {
                // Bilinmeyen URL tipi, dizi/film olarak dene
                val document = app.get(fixedUrl, headers = headers).document
                val title = document.selectFirst("h1.dp-series-title, h1.fp-title, h1")?.text()?.trim()
                    ?: return null
                if (document.selectFirst(".dp-season-btns") != null) {
                    loadSeriesPage(fixedUrl, document)
                } else {
                    loadMoviePage(fixedUrl, document)
                }
            }
        }
    }

    private suspend fun loadSeriesPage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.dp-series-title")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst(".dp-hero")?.attr("style")
            ?.let { Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("p.dp-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = doc.selectFirst(".dp-info-val")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.selectFirst("meta[property=og:video:release_date]")?.attr("content")?.take(4)?.toIntOrNull()

        val episodeList = ArrayList<Episode>()

        // 1. bolum_yukle.php'den bölümleri çek
        val slug = url.removePrefix("$mainUrl/dizi/").removePrefix("/dizi/").trim('/')
        doc.select(".dp-season-btn").forEach { seasonBtn ->
            val seasonNumber = seasonBtn.attr("data-season").toIntOrNull()
                ?: seasonBtn.text().filter { it.isDigit() }.toIntOrNull()
                ?: return@forEach

            try {
                val epResponse = app.get(
                    "$mainUrl/bolum_yukle.php?slug=${URLEncoder.encode(slug, "UTF-8")}&sezon=$seasonNumber",
                    headers = ajaxHeaders,
                    referer = url
                )
                val episodes: List<EpisodeJson> = mapper.readValue(epResponse.text)
                episodes.forEachIndexed { index, ep ->
                    episodeList.add(
                        newEpisode("$mainUrl/bolum/${ep.slug}") {
                            this.name = ep.baslik ?: "Sezon $seasonNumber Bölüm ${index + 1}"
                            this.season = seasonNumber
                            this.episode = index + 1
                        }
                    )
                }
            } catch (_: Exception) {
                // JSON parse edilemezse geç
            }
        }

        // 2. Yedek: İlk/Son bölüm butonları varsa ekle
        if (episodeList.isEmpty()) {
            doc.select(".dp-hero-btns a[href*=/bolum/]").forEachIndexed { index, btn ->
                val epUrl = fixUrl(btn.attr("href"))
                val label = btn.text().trim()
                episodeList.add(
                    newEpisode(epUrl) {
                        this.name = label
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
        val doc = document ?: app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.fp-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".fp-poster img")?.let { extractImage(it) }

        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun loadEpisodePage(url: String): LoadResponse? {
        // Bölüm sayfası açılınca series sayfasına yönlendir veya doğrudan oynat
        val doc = app.get(url, headers = headers, referer = mainUrl).document

        // JSON-LD'den veya başlıktan dizi adını bul
        val title = doc.selectFirst("title")?.text()
            ?.replace(Regex("\\d+\\. Sezon \\d+\\. Bölüm izle.*"), "")
            ?.replace("izle", "")
            ?.trim()
            ?: "Video"

        // Eğer bölüm sayfasında iframe varsa direkt oynat
        val episode = newEpisode(url) {
            this.name = doc.selectFirst("title")?.text()?.trim() ?: "Bölüm"
            this.season = Regex("(\\d+)\\. Sezon").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            this.episode = Regex("(\\d+)\\. Bölüm").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        }
    }

    // ================= 4. VİDEO LİNKLERİ =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixUrl(data)

        // Film sayfasındaysak embed URL'sini oluştur
        val embedUrl = when {
            "/film/" in fixedData -> {
                val slug = fixedData.removePrefix("$mainUrl/film/").removePrefix("/film/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=https://dizipal2083.com&type=film"
            }
            "/bolum/" in fixedData -> {
                val slug = fixedData.removePrefix("$mainUrl/bolum/").removePrefix("/bolum/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=https://dizipal2083.com&type=dizi"
            }
            else -> {
                // Dizi ana sayfasından gelindiyse, sayfadaki iframe'i dene
                val doc = app.get(fixedData, headers = headers).document
                doc.selectFirst("iframe")?.attr("src")?.let { return resolveEmbed(it, callback) }
                return false
            }
        }

        return resolveEmbed(embedUrl, callback)
    }

    private suspend fun resolveEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val fixedEmbed = when {
            embedUrl.startsWith("http") -> embedUrl
            embedUrl.startsWith("//") -> "https:$embedUrl"
            else -> mainUrl + embedUrl
        }

        return try {
            val html = app.get(fixedEmbed, headers = headers, referer = mainUrl).text
            val hlsUrl = Regex("""var src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)

            if (!hlsUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedEmbed
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

    // ================= YARDIMCI FONKSİYONLAR =================
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
        val slug: String,
        val title: String,
        val type: String,
        val poster: String? = null,
        val year: Int? = null
    )

    data class EpisodeJson(
        val slug: String,
        val baslik: String? = null,
        val alt: String? = null
    )
}
