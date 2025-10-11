package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class EgyDeadProvider : MainAPI() {
    // This is the known working base of the provider.
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

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = cloudflareKiller)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = headers, data = data, cookies = cookies, interceptor = cloudflareKiller).document
            }
            return document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = app.get(url, interceptor = cloudflareKiller).document
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
        val document = app.get("$mainUrl/?s=$query", interceptor = cloudflareKiller).document
        return document.select("li.movieItem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val isSeries = document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodes = document.select("div.EpsList li a").mapNotNull { ep ->
                val epNum = ep.attr("title").substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
                newEpisode(ep.attr("href")) {
                    this.name = ep.text().trim()
                    this.episode = epNum
                }
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
    // START of THE FIX
    // The previous implementation was overly complex and caused errors.
    // `apmap` already runs its lambda in a suspendable context, so `launch` is not needed.
    // We use AtomicBoolean to safely track if any link was found across parallel executions.
    // =================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")

        if (servers.isEmpty()) return false

        // A thread-safe boolean to track if we have successfully found any link.
        val foundAUrl = AtomicBoolean(false)

        // `apmap` executes the code block for each server in parallel.
        // The lambda passed to it is already a `suspend` function.
        servers.apmap { serverLi: Element ->
            try {
                val link = serverLi.attr("data-link")
                if (link.isNotBlank()) {
                    // We can call `loadExtractor` directly because we are in a suspend context.
                    loadExtractor(link, data, subtitleCallback) { foundLink ->
                        callback(foundLink)
                        // Set the flag to true. This is safe to do from multiple threads.
                        foundAUrl.set(true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Return true if any of the parallel executions found a URL, false otherwise.
        return foundAUrl.get()
    }
    // =================================================================================
    // END of THE FIX
    // =================================================================================
}
