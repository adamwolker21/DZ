package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log 
import java.util.Base64 

private val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "sec-ch-ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
    "sec-ch-ua-mobile" to "?1",
    "sec-ch-ua-platform" to """"Android"""",
)

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv6.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private suspend fun getDocument(url: String): Document {
        return app.get(url, interceptor = cloudflareKiller, headers = BROWSER_HEADERS).document
    }

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = cloudflareKiller, headers = BROWSER_HEADERS)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val postHeaders = BROWSER_HEADERS + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded", "Referer" to url, "Origin" to mainUrl,
                    "sec-fetch-dest" to "document", "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin", "sec-fetch-user" to "?1"
                )
                return app.post(url, headers = postHeaders, data = mapOf("View" to "1"), cookies = cookies, interceptor = cloudflareKiller).document
            }
            return document
        } catch (e: Exception) { e.printStackTrace(); return null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = getDocument(url)
        val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
        val isSeries = title.contains("مسلسل") || title.contains("الموسم")

        return if (isSeries) newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getDocument("$mainUrl/?s=$query").select("li.movieItem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: throw ErrorLoadingException("Could not load page title")
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val duration = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val isSeries = document.selectFirst("li:has(span:contains(القسم)) a")?.text()?.contains("مسلسلات") == true || pageTitle.contains("مسلسل") || pageTitle.contains("الموسم") || document.select("div.EpsList").isNotEmpty()

        return if (isSeries) {
            val episodesDoc = getWatchPage(url) ?: document
            val episodes = episodesDoc.select("div.EpsList li a").mapNotNull { ep ->
                ep.attr("title").substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()?.let {
                    newEpisode(ep.attr("href")) { name = ep.text().trim(); episode = it }
                }
            }.distinctBy { it.episode }.toMutableList()
            val seriesTitle = pageTitle.replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()
            val currentEpNum = pageTitle.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
            if (currentEpNum != null && episodes.none { it.episode == currentEpNum }) {
                episodes.add(newEpisode(url) { name = pageTitle.substringAfter(seriesTitle).trim().ifBlank { "حلقة $currentEpNum" }; this.episode = currentEpNum })
            }
            newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()
            newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags; this.duration = duration
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        
        // 1. Process Streaming Servers (with debugging)
        watchPageDoc.select("div.mob-servers li").apmap { serverLi ->
            Log.d("EgyDeadProvider_HTML_DEBUG", "Streaming Server LI Element: ${serverLi.outerHtml()}")

            var link = serverLi.attr("data-link")
            if (link.isBlank()) {
                val onclickAttr = serverLi.attr("onclick")
                val base64Url = Regex("""GoTo\('([^']+)""").find(onclickAttr)?.groupValues?.get(1)
                if (base64Url != null) {
                    link = try { String(Base64.getDecoder().decode(base64Url)) } 
                    catch (e: Exception) { Log.e("EgyDeadProvider", "Base64 decoding failed for '$base64Url': ${e.message}"); "" }
                }
            }
            if (link.isNotBlank()) loadExtractor(link, data, subtitleCallback, callback)
        }

        // 2. Process Download Links (with corrected selector)
        watchPageDoc.select("ul.donwload-servers-list li").apmap { downloadLi ->
            Log.d("EgyDeadProvider_HTML_DEBUG", "Download LI Element: ${downloadLi.outerHtml()}")

            val link = downloadLi.selectFirst("a.ser-link")?.attr("href")
            if (!link.isNullOrBlank()) {
                Log.d("EgyDeadProvider", "Found download link: $link")
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
