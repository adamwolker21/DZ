package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

// Define a tag for logging that can be easily filtered in logcat
private const val TAG = "EgyDeadProvider"

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

    private val webViewResolver by lazy { WebViewResolver(Regex("""\?__cf_chl_tk=""")) }

    private suspend fun getWatchPage(url: String): Document? {
        Log.d(TAG, "getWatchPage called for URL: $url")
        try {
            val initialResponse = app.get(url, interceptor = webViewResolver)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl,
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = headers, data = data, cookies = cookies, interceptor = webViewResolver).document
            }
            return document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get watch page", e) // Log the full exception
            return null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        Log.d(TAG, "Requesting getMainPage URL: $url")
        try {
            val document = app.get(url, interceptor = webViewResolver).document
            Log.d(TAG, "getMainPage HTML received. Title: ${document.title()}")
            // Uncomment the line below for VERY detailed debugging to see the full HTML
            // Log.d(TAG, "Full HTML: ${document.html()}")

            val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
            Log.d(TAG, "Found ${home.size} items on the main page for '${request.name}'.")
            if (home.isEmpty()) {
                Log.w(TAG, "No items found. The CSS selector 'li.movieItem' might be incorrect or the page is empty.")
            }
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed", e)
            throw e // Re-throw the exception to let the app handle it
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val linkTag = this.selectFirst("a") ?: return null
            val href = linkTag.attr("href")
            val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
            val posterUrl = this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("src")
            val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
            val isSeries = title.contains("مسلسل") || title.contains("الموسم")

            Log.d(TAG, "Parsed Item: Title='${cleanedTitle}', Poster='${posterUrl}'")

            return if (isSeries) {
                newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            } else {
                newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search result item: ${this.html()}", e)
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        Log.d(TAG, "Searching with URL: $url")
        val document = app.get(url, interceptor = webViewResolver).document
        val results = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        Log.d(TAG, "Search for '$query' found ${results.size} items.")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading URL: $url")
        try {
            val document = app.get(url, interceptor = webViewResolver).document
            Log.d(TAG, "Load page HTML received. Title: ${document.title()}")
            val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: run {
                Log.w(TAG, "Could not find page title in load function.")
                return null
            }
            // ... (rest of the load function is the same, no need to repeat it all)
            // The existing code from v3 for the rest of this function is fine.
            val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
            val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
            val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
            var plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
            val country = document.selectFirst("li:has(span:contains(البلد)) a")?.text()
            val channels = document.select("li:has(span:contains(القناه)) a").joinToString(", ") { it.text() }
            val durationText = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()
            val duration = durationText?.filter { it.isDigit() }?.toIntOrNull()
            val extraInfo = mutableListOf<String>()
            country?.let { extraInfo.add("البلد: $it") }
            if(channels.isNotBlank()) { extraInfo.add("القناة: $channels") }
            if(extraInfo.isNotEmpty()) {
                plot += "<br><br>${extraInfo.joinToString(" | ")}"
            }
            val categoryText = document.selectFirst("li:has(span:contains(القسم)) a")?.text() ?: ""
            val isSeries = categoryText.contains("مسلسلات") || pageTitle.contains("مسلسل") || pageTitle.contains("الموسم")

            if (isSeries) {
                val episodesDoc = getWatchPage(url) ?: document
                val seriesTitle = pageTitle.replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()
                val episodes = episodesDoc.select("div.EpsList li a").mapNotNull { epElement ->
                    val href = epElement.attr("href")
                    val epNum = epElement.attr("title").substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull() ?: return@mapNotNull null
                    newEpisode(href) {
                        this.name = epElement.text().trim(); this.episode = epNum
                    }
                }.toMutableList()
                val currentEpNum = pageTitle.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
                if (currentEpNum != null && episodes.none { it.episode == currentEpNum }) {
                    episodes.add(newEpisode(url) {
                        this.name = pageTitle.substringAfter(seriesTitle).trim().ifBlank { "حلقة $currentEpNum" }; this.episode = currentEpNum
                    })
                }
                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                    this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
                    this.duration = duration
                }
            } else {
                val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()
                return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
                    this.duration = duration
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to load URL: $url", e)
            return null
        }
    }
    
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks called for data: $data")
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")
        Log.d(TAG, "Found ${servers.size} servers.")
        servers.apmap { server ->
            try {
                val link = server.attr("data-link")
                if (link.isNotBlank()) {
                    Log.d(TAG, "Loading extractor for link: $link")
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load extractor for a server", e)
            }
        }
        return true
    }
}
