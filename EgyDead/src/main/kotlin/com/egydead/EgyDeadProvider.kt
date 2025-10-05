package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv6.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // Define the main page sections
    override val mainPage = mainPageOf(
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
    )

    // Fetch and parse the main page content
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}?page=$page/"
        }

        val document = app.get(url).document
        val home = document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Helper function to parse an element into a SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        val cleanedTitle = title.replace("مشاهدة", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("مترجم", "")
            .trim()

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
        val document = app.get(searchUrl).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    // Load movie/series details
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extract the main title from the page title or a heading
        val title = document.selectFirst("div.Title > h1")?.text()?.trim() 
            ?: document.title().substringBefore(" - ").trim()

        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim()

        // Find details from the info list
        val infoList = document.select("div.single-content > ul > li")
        val year = infoList.find { it.text().contains("السنه") }
            ?.selectFirst("a")?.text()?.toIntOrNull()
        val tags = infoList.find { it.text().contains("النوع") }
            ?.select("a")?.map { it.text() }

        // Check if it is a series by looking for the episodes list
        val episodesList = document.select("div.EpsList li a")
        if (episodesList.isNotEmpty()) {
            val episodes = episodesList.mapNotNull { epElement ->
                val epHref = epElement.attr("href")
                val epTitle = epElement.attr("title")
                val epNum = epTitle.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()
                
                newEpisode(epHref) {
                    name = epElement.text().trim() // e.g., "حلقه 1"
                    episode = epNum
                    season = 1 // Assuming single season for now as there's no season selector
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // It's a Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // Placeholder for loading video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // To be implemented in the next steps
        return false
    }
}
