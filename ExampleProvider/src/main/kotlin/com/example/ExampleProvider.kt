package com.example.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal2083.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudstream'in kendi HTTP istemcisi (NiceHttp) için header'lar
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // ================= 1. ANA SAYFA VİTRİNİ =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers).document
        val homePageList = ArrayList<HomePageList>()

        // 1. KATEGORİ: Trend Diziler
        val trendItems = ArrayList<SearchResponse>()
        document.select(".trending-container .card-info, .slider-container .card-info").forEach { element ->
            parseCard(element)?.let { trendItems.add(it) }
        }
        if (trendItems.isNotEmpty()) {
            homePageList.add(HomePageList("Trend Diziler", trendItems))
        }

        // 2. KATEGORİ: Son Eklenen Diziler ve Filmler
        val recentItems = ArrayList<SearchResponse>()
        document.select(".recent-container .card-info, .grid-container .card-info").forEach { element ->
            parseCard(element)?.let { recentItems.add(it) }
        }
        if (recentItems.isNotEmpty()) {
            homePageList.add(HomePageList("Son Eklenenler", recentItems))
        }

        // B PLANI: Yukarıdaki kapsayıcılar bulunamazsa tüm .card-info'ları yakala
        if (homePageList.isEmpty()) {
            val allItems = ArrayList<SearchResponse>()
            document.select(".card-info, .card").forEach { element ->
                parseCard(element)?.let { allItems.add(it) }
            }
            if (allItems.isNotEmpty()) {
                homePageList.add(HomePageList("Güncel İçerikler", allItems))
            }
        }

        return newHomePageResponse(homePageList, false)
    }

    private fun parseCard(infoBlock: Element): SearchResponse? {
        val parent = infoBlock.parent() ?: infoBlock

        val title = infoBlock.selectFirst(".card-title")?.text()?.trim()
            ?: parent.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val imgTag = parent.selectFirst("img")
        val poster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgTag?.attr("src")
            ?: ""

        val href = if (parent.tagName() == "a") parent.attr("href") else parent.selectFirst("a")?.attr("href")
        if (href.isNullOrBlank()) return null

        val url = fixUrl(href)

        val typeBadge = parent.selectFirst(".card-badge.type")?.text()?.trim()?.lowercase()
        val type = if (typeBadge?.contains("dizi") == true || title.lowercase().contains("sezon")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ================= 2. ARAMA =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl, headers = headers).document

        val results = ArrayList<SearchResponse>()
        document.select(".card-info, .card, .search-result, article").forEach { element ->
            parseCard(element)?.let { results.add(it) }
        }
        return results
    }

    // ================= 3. DETAY SAYFASI =================
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = headers).document

        val title = document.selectFirst("h1.title, h1.film-title, .page-title h1, h1")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst(".poster img, .cover img, .film-poster img")
            ?.let { extractImage(it) }

        val description = document.selectFirst(
            ".summary, .description, .plot, .content p, .film-content, [itemprop=description], meta[name=description]"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val tags = document.select(".genres a, .category a, .film-genres a, [rel=category tag]").map { it.text().trim() }
        val year = document.selectFirst(".year, .release-year, [itemprop=datePublished]")?.text()?.trim()?.toIntOrNull()

        // Sezon/bölüm yapısı var mı?
        val seasons = document.select(".season-item, .season-list li, .season-tabs a, [data-season]")
        val episodes = document.select(".episode-item, .episode-list a, [data-episode]")

        return if (seasons.isNotEmpty() || episodes.isNotEmpty()) {
            // ---------------- DİZİ ----------------
            val episodeList = ArrayList<Episode>()

            if (seasons.isNotEmpty()) {
                seasons.forEachIndexed { index, seasonEl ->
                    val seasonNumber = seasonEl.attr("data-season").toIntOrNull()
                        ?: seasonEl.text().filter { it.isDigit() }.toIntOrNull()
                        ?: (index + 1)

                    val targetId = seasonEl.attr("href").removePrefix("#")
                    val seasonEpisodes = if (targetId.isNotBlank()) {
                        document.select("$targetId .episode-item, $targetId .episode-list a, $targetId [data-episode]")
                    } else {
                        emptyList()
                    }

                    seasonEpisodes.forEachIndexed { epIndex, epEl ->
                        extractEpisode(epEl, seasonNumber, epIndex + 1)?.let { episodeList.add(it) }
                    }
                }
            }

            if (episodeList.isEmpty()) {
                episodes.forEachIndexed { index, epEl ->
                    val seasonNumber = epEl.attr("data-season").toIntOrNull()
                        ?: epEl.parents().select("[data-season]").first()?.attr("data-season")?.toIntOrNull()
                        ?: 1
                    extractEpisode(epEl, seasonNumber, index + 1)?.let { episodeList.add(it) }
                }
            }

            newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodeList) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            // ---------------- FİLM ----------------
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    // ================= 4. VİDEO LİNKLERİ =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers, referer = mainUrl).document

        // 1. Sayfadaki iframe'i bul ve çözümle
        val iframeUrl = document.extractIframe()
            ?: document.selectFirst(".video-player iframe, #player iframe, iframe[src*=.mp4], iframe[src*=.m3u8]")?.attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            resolveVideoUrl(iframeUrl, callback)
        }

        // 2. Sayfanın kendi HTML'inde doğrudan video linki var mı?
        extractDirectLinks(document.html(), callback)

        return true
    }

    // ================= YARDIMCI FONKSİYONLAR =================
    private fun extractImage(img: Element): String? {
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return when {
            src.isBlank() -> null
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> mainUrl + src
        }
    }

    private fun extractEpisode(
        element: Element,
        season: Int,
        fallbackEpisode: Int
    ): Episode? {
        val link = element.selectFirst("a")?.attr("href")
            ?: element.attr("data-link").takeIf { it.isNotBlank() }
            ?: element.attr("href").takeIf { it.isNotBlank() }
            ?: return null

        val epNumber = element.attr("data-episode").toIntOrNull()
            ?: element.text().filter { it.isDigit() }.toIntOrNull()
            ?: fallbackEpisode

        val epTitle = element.selectFirst(".episode-title, .title")?.text()?.trim()
            ?: "Sezon $season Bölüm $epNumber"

        return newEpisode(fixUrl(link)) {
            this.name = epTitle
            this.season = season
            this.episode = epNumber
        }
    }

    private fun Document.extractIframe(): String? {
        return this.selectFirst("iframe")?.let { iframe ->
            iframe.attr("src").ifBlank { iframe.attr("data-src") }
        }
    }

    private suspend fun resolveVideoUrl(iframeUrl: String, callback: (ExtractorLink) -> Unit) {
        val fixedIframe = when {
            iframeUrl.startsWith("http") -> iframeUrl
            iframeUrl.startsWith("//") -> "https:$iframeUrl"
            else -> mainUrl + iframeUrl
        }

        try {
            val iframeDoc = app.get(fixedIframe, headers = headers, referer = mainUrl).document
            extractDirectLinks(iframeDoc.html(), callback, fixedIframe)

            iframeDoc.select("video source, source").forEach { source ->
                val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
                addVideoLink(videoUrl, callback, fixedIframe)
            }
        } catch (_: Exception) {
            // İframe erişilemezse geç
        }
    }

    private suspend fun extractDirectLinks(html: String, callback: (ExtractorLink) -> Unit, referer: String? = null) {
        val m3u8Regex = Regex("""(https?://[^"'<>\s]+\.m3u8[^"'<>\s]*)""")
        m3u8Regex.findAll(html).forEach { match ->
            addVideoLink(match.value, callback, referer, ExtractorLinkType.M3U8)
        }

        val mp4Regex = Regex("""(https?://[^"'<>\s]+\.mp4[^"'<>\s]*)""")
        mp4Regex.findAll(html).forEach { match ->
            addVideoLink(match.value, callback, referer, ExtractorLinkType.VIDEO)
        }

        val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
        fileRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            addVideoLink(url, callback, referer, type)
        }
    }

    private suspend fun addVideoLink(
        url: String?,
        callback: (ExtractorLink) -> Unit,
        referer: String? = null,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ) {
        if (url.isNullOrBlank()) return
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url,
                type = type
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
