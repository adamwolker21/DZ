package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

// =================================================================================
// START of v42 - The Merged File Fix
// All extractor logic is now inside the Provider to eliminate intermediate function calls.
// =================================================================================

// --- Helper variables and functions moved from the old extractor file ---
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)
private val cloudflareKiller by lazy { CloudflareKiller() }

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("Extractor", "safeGetAsDocument FAILED for $url: ${e.message}")
        null
    }
}

// --- Main Provider Class ---
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

    // --- Standard Provider Functions (unchanged) ---
    private suspend fun getWatchPage(url: String): Document? { /* ... The same working code ... */
        try {
            return app.get(url, interceptor = cloudflareKiller).document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse { /* ... */
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = app.get(url, interceptor = cloudflareKiller).document
        val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }
    private fun Element.toSearchResult(): SearchResponse? { /* ... */
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
        val isSeries = title.contains("مسلسل") || title.contains("الموسم")
        return if (isSeries) {
            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }
    override suspend fun search(query: String): List<SearchResponse> { /* ... */
        val document = app.get("$mainUrl/?s=$query", interceptor = cloudflareKiller).document
        return document.select("li.movieItem").mapNotNull { it.toSearchResult() }
    }
    override suspend fun load(url: String): LoadResponse? { /* ... The same working code ... */
        val document = app.get(url, interceptor = cloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val isSeries = document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodes = document.select("div.EpsList li a").mapNotNull { ep ->
                val epNum = ep.text().filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
                newEpisode(ep.attr("href")) { this.name = ep.text().trim(); this.episode = epNum }
            }
            val seriesTitle = pageTitle.replace(Regex("""(مسلسل|الموسم|الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()
            return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    // =================================================================================
    // THE FINAL FIX: `loadLinks` now contains all the logic directly.
    // =================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")
        if (servers.isEmpty()) return false

        // A simple, sequential loop that waits for each server.
        for (server in servers) {
            val link = server.attr("data-link")
            if (link.isBlank()) continue

            // The "Manager" now checks the link and decides which "department" to send it to.
            // All departments are now in-house.
            when {
                // --- StreamHG Department ---
                link.contains("hglink.to") || link.contains("kravaxxa.com") -> {
                    invokeStreamHG(link, data, callback)
                }
                // --- DoodStream Department ---
                link.contains("dood") || link.contains("dsvplay") -> {
                    invokeDoodStream(link, data, callback)
                }
                // You can add more 'when' cases here for other servers if needed
                // For now, we rely on the built-in extractors for the rest.
                else -> {
                     loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // --- Private "Departments" (Extractor Logic) inside the Provider ---

    private suspend fun invokeStreamHG(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val potentialHosts = listOf("kravaxxa.com", "cavanhabg.com", "dumbalag.com", "davioad.com", "haxloppd.com")
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            try {
                val finalPageUrl = "https://$host/e/$videoId"
                val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

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
                        newExtractorLink(source = "StreamHG", name = "StreamHG", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return // Success, exit the function
                }
            } catch (e: Exception) {
                // Failed on this host, try the next one
            }
        }
    }

    private suspend fun invokeDoodStream(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val mainUrl = if(url.contains("dsvplay")) "dsvplay.com" else "doodstream.com"
            val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
            val responseText = app.get(newUrl, referer = referer, headers = BROWSER_HEADERS).text
            
            val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
            if (doodToken.isBlank()) return
            
            val md5PassUrl = "https://$mainUrl/pass_md5/$doodToken"
            val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
            callback(
                newExtractorLink(source = "DoodStream", name = "DoodStream", url = trueUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = newUrl
                }
            )
        } catch (e: Exception) {
            // Failed
        }
    }
}
