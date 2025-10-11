package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.json.JSONObject
import org.jsoup.Jsoup // Import Jsoup directly for manual parsing
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

// =================================================================================
// START of v46 - The Final Manual Fix
// All logic is now inside the Provider, and we are manually parsing HTML
// to bypass the `NoSuchMethodError`.
// =================================================================================

// --- Helper variables and functions moved here for the merged logic ---
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

// This separate cloudflareKiller instance is for our manual extraction logic.
private val extractorCloudflareKiller by lazy { CloudflareKiller() }

// --- Main Provider Class ---
class EgyDeadProvider : MainAPI() {
    // Using the stable base provider code.
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

    // This is the provider's own cloudflareKiller.
    private val providerCloudflareKiller by lazy { CloudflareKiller() }

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = providerCloudflareKiller)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val postHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded", "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = postHeaders, data = data, cookies = cookies, interceptor = providerCloudflareKiller).document
            }
            return document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = app.get(url, interceptor = providerCloudflareKiller).document
        val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
        val isSeries = title.contains("مسلسل") || title.contains("الموسم")
        return if (isSeries) newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = providerCloudflareKiller).document
        return document.select("li.movieItem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = providerCloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val isSeries = document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodes = document.select("div.EpsList li a").mapNotNull { ep ->
                val epNum = ep.attr("title").substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull() ?: return@mapNotNull null
                newEpisode(ep.attr("href")) { this.name = ep.text().trim(); this.episode = epNum }
            }
            val seriesTitle = pageTitle.replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot
            }
        } else {
            val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()
            return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")
        if (servers.isEmpty()) return false

        for (server in servers) {
            val link = server.attr("data-link")
            if (link.isBlank()) continue
            
            Log.d("EgyDeadProvider", "Processing server link: $link")

            try {
                if (link.contains("hglink.to")) {
                    val potentialHosts = listOf("kravaxxa.com", "cavanhabg.com", "dumbalag.com")
                    val videoId = link.substringAfterLast("/")
                    if (videoId.isBlank()) continue

                    for (host in potentialHosts) {
                        try {
                            val finalPageUrl = "https://$host/e/$videoId"
                            
                            // =================== v46 MANUAL PARSING FIX ===================
                            // Step 1: Get the raw HTML text.
                            val htmlText = app.get(finalPageUrl, referer = data, headers = BROWSER_HEADERS, interceptor = extractorCloudflareKiller, verify = false).text
                            // Step 2: Manually parse the text into a Jsoup Document.
                            val doc = Jsoup.parse(htmlText)
                            // =============================================================

                            val packedJs = doc.select("script")
                                .map { it.data() }
                                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                                .maxByOrNull { it.length } ?: continue
                            
                            val unpacked = getAndUnpack(packedJs)
                            val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";").trim()
                            val jsonObject = JSONObject(jsonObjectString)
                            val m3u8Link = jsonObject.getString("hls2")

                            if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                                callback(
                                    newExtractorLink("StreamHG", "StreamHG", m3u8Link, type = ExtractorLinkType.M3U8) {
                                        this.referer = finalPageUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                break 
                            }
                        } catch (e: Exception) {
                            Log.e("EgyDeadProvider", "StreamHG failed on host '$host': ${e.message}")
                        }
                    }
                } else {
                    // For other servers, we can still use the built-in `loadExtractor`.
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("EgyDeadProvider", "Failed to process server $link: ${e.message}")
            }
        }
        return true
    }
}
