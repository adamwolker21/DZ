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
        return try {
            app.get(url, interceptor = cloudflareKiller).document
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }

        val document = app.get(url, interceptor = cloudflareKiller).document
        val home = document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")

        val cleanedTitle = title.replace("مشاهدة", "").trim()
            .replace(Regex("^(فيلم|مسلسل)"), "").trim()

        val isSeries = title.contains("مسلسل") || title.contains("الموسم")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(cleanedTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, interceptor = cloudflareKiller).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val duration = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val isSeries = document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodes = document.select("div.EpsList li a").mapNotNull { epElement ->
                val href = epElement.attr("href")
                val epNum = epElement.text().filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
                
                newEpisode(href) {
                    this.name = epElement.text().trim()
                    this.episode = epNum
                }
            }.distinctBy { it.episode }
            
            val seriesTitle = pageTitle
                .replace(Regex("""(مسلسل|الموسم|الحلقة \d+|مترجمة|الاخيرة)"""), "")
                .trim()
            
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
             val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()

            return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
            }
        }
    }
    
    // =================== v36 THE FINAL FIX ===================
    // The loadLinks function now correctly waits for the extractors to finish their job.
    // It suspends its own execution and only resumes when at least one link has been found
    // or when all extractors have been tried. This solves the "manager leaving early" problem.
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")

        if (servers.isEmpty()) return false

        return suspendCancellableCoroutine { continuation ->
            val hasFoundLink = AtomicBoolean(false)
            var remainingServers = servers.size

            servers.apmap { serverLi ->
                val link = serverLi.attr("data-link")
                if (link.isNotBlank()) {
                    loadExtractor(link, data, subtitleCallback) { foundLink ->
                        // This is the callback from the extractor.
                        // We pass the link up to the app.
                        callback(foundLink)
                        // If this is the first link we've found, we can resume the coroutine
                        // and declare the function a success.
                        if (hasFoundLink.compareAndSet(false, true)) {
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }
                    }
                }
                // This logic ensures that if all servers are tried and none return a link,
                // the function will eventually resume with 'false'.
                synchronized(this) {
                    remainingServers--
                    if (remainingServers == 0 && !hasFoundLink.get()) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            }
        }
    }
    // =========================================================
}
