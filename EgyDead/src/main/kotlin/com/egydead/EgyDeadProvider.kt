package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import android.util.Log

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
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to "أفلام عربية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "أفلام هندية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "مسلسلات أجنبية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to "مسلسلات عربية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
    )

    // A lazy-loaded instance of the CloudflareKiller interceptor
    private val cloudflareKiller by lazy { CloudflareKiller() }

    // This function handles the initial POST request to reveal the server links
    private suspend fun getWatchPage(url: String): com.lagradost.cloudstream3.network.response? {
        return try {
            app.post(
                url,
                // This interceptor is crucial for bypassing Cloudflare on the main site
                interceptor = cloudflareKiller,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl,
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-user" to "?1"
                ),
                data = mapOf("View" to "1")
            )
        } catch (e: Exception) {
            Log.e("EgyDead", "getWatchPage failed for url: $url", e)
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
        val posterUrl = this.selectFirst("img")?.attr("src")

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
                val titleAttr = epElement.attr("title")
                val epNum = titleAttr.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull() ?: return@mapNotNull null

                newEpisode(href) {
                    this.name = epElement.text().trim()
                    this.episode = epNum
                }
            }.distinctBy { it.episode }

            val seriesTitle = pageTitle.replace(Regex("""(مسلسل|الموسم \d+|الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()

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

    // =================================================================================================
    // START OF THE FINAL, ROBUST loadLinks FUNCTION (v40)
    // =================================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Get the watch page, returning false if it fails
        val response = getWatchPage(data) ?: return false
        val document = response.document

        Log.d("EgyDead", "Loading links for URL: $data")
        Log.d("EgyDead", "Document title: ${document.title()}")

        // A state variable to track if any link was successfully found
        var linksFound = false

        // A new callback that wraps the original one. It will set our flag to true
        // the first time it successfully receives a link.
        val newCallback = { link: ExtractorLink ->
            linksFound = true
            callback(link)
        }

        val servers: List<Element> = document.select("div.mob-servers li")
        Log.d("EgyDead", "Found ${servers.size} potential server elements")
        servers.forEachIndexed { index, server ->
            val link: String = server.attr("data-link")
            val name: String = server.text()
            Log.d("EgyDead", "Server #$index | Name: $name | Link: $link")
        }

        // Use apmap to process all servers in parallel. It will wait for all of them to finish.
        servers.apmap { server ->
            val link: String = server.attr("data-link")
            if (link.isNotBlank()) {
                // We pass our new, enhanced callback to loadExtractor
                loadExtractor(link, data, subtitleCallback, newCallback)
            }
        }

        // The function now returns true ONLY if the callback was ever invoked.
        return linksFound
    }
    // =================================================================================================
    // END OF THE FINAL loadLinks FUNCTION
    // =================================================================================================
}
