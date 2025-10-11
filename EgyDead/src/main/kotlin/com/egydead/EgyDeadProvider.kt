package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// =================================================================================
// START of v48 - The Final Preload Fix
// This version implements your pre-loading idea correctly.
// =================================================================================

// --- Helper variables and functions for the pre-loading logic ---
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)
private val extractorCloudflareKiller by lazy { CloudflareKiller() }

// This is our "waiting room" or cache. It will store the pre-loaded links.
// Key: The movie/episode URL. Value: The extracted StreamHG link.
private val preloadedLinks = ConcurrentHashMap<String, ExtractorLink>()


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
    
    private val providerCloudflareKiller by lazy { CloudflareKiller() }

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = providerCloudflareKiller)
            return initialResponse.document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse { /* ... */
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = app.get(url, interceptor = providerCloudflareKiller).document
        val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? { /* ... */
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
        val isSeries = title.contains("مسلسل") || title.contains("الموسم")
        return if (isSeries) newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> { /* ... */
        val document = app.get("$mainUrl/?s=$query", interceptor = providerCloudflareKiller).document
        return document.select("li.movieItem").mapNotNull { it.toSearchResult() }
    }

    // =================================================================================
    // THE PRELOAD LOGIC IS IMPLEMENTED HERE, IN `load`
    // =================================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = providerCloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val isSeries = document.select("div.EpsList").isNotEmpty()

        // --- PRELOAD STARTS HERE ---
        // We launch a new background task that will not block the UI.
        cs.launch {
            Log.d("Preload", "Starting pre-load for: $url")
            val watchPageDoc = getWatchPage(url) ?: return@launch
            val streamHgLink = watchPageDoc.select("div.mob-servers li")
                .map { it.attr("data-link") }
                .find { it.contains("hglink.to") }

            if (streamHgLink != null) {
                // We found a StreamHG server, let's extract it in the background.
                val potentialHosts = listOf("kravaxxa.com", "cavanhabg.com", "dumbalag.com")
                val videoId = streamHgLink.substringAfterLast("/")
                if (videoId.isNotBlank()) {
                    for (host in potentialHosts) {
                        try {
                            val finalPageUrl = "https://$host/e/$videoId"
                            val htmlText = app.get(finalPageUrl, referer = url, headers = BROWSER_HEADERS, interceptor = extractorCloudflareKiller, verify = false).text
                            val doc = Jsoup.parse(htmlText)
                            val packedJs = doc.select("script").map { it.data() }.filter { it.contains("eval(function(p,a,c,k,e,d)") }.maxByOrNull { it.length } ?: continue
                            val unpacked = getAndUnpack(packedJs)
                            val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";").trim()
                            val jsonObject = JSONObject(jsonObjectString)
                            val m3u8Link = jsonObject.getString("hls2")

                            if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                                Log.d("Preload", "SUCCESS: Pre-loaded link found: $m3u8Link")
                                // We store the found link in our cache.
                                val extractorLink = newExtractorLink("StreamHG", "StreamHG (Fast)", m3u8Link, type = ExtractorLinkType.M3U8) {
                                    this.referer = finalPageUrl
                                }
                                preloadedLinks[url] = extractorLink
                                break // Exit host loop
                            }
                        } catch (e: Exception) {
                           Log.e("Preload", "Pre-load failed on host '$host': ${e.message}")
                        }
                    }
                }
            }
        }
        // --- PRELOAD ENDS HERE ---
        
        // The rest of the function continues normally while the preload runs in the background.
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
    
    // =================================================================================
    // `loadLinks` NOW CHECKS THE CACHE FIRST
    // =================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // --- INSTANT DELIVERY ---
        // First, check if we have a pre-loaded link in our "waiting room".
        val preloaded = preloadedLinks.remove(data) // Use remove to get it and clear it.
        if (preloaded != null) {
            Log.d("loadLinks", "Found pre-loaded link, delivering instantly!")
            callback(preloaded)
        }
        // --- END INSTANT DELIVERY ---

        // This function now returns to the simple "launch and forget" method,
        // which we know works for the other built-in extractors.
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")

        servers.apmap { server ->
            val link = server.attr("data-link")
            if (link.isNotBlank()) {
                // We skip the StreamHG link if we already pre-loaded it.
                if (preloaded != null && link.contains("hglink.to")) {
                    return@apmap
                }
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
