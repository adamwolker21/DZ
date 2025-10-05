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
        // Construct the URL based on the page number
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            // Corrected pagination URL format
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

        // Clean up the title from extra words
        val cleanedTitle = title.replace("مشاهدة", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("مترجم", "")
            .trim()

        // Determine if it's a TV series or a Movie based on title
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

        // Extract the base title, removing season and episode info
        val rawTitle = document.selectFirst("h1.Title")?.text()?.trim() ?: return null
        val title = rawTitle.substringBefore("الموسم").substringBefore("الحلقة").trim()

        val posterUrl = document.selectFirst("div.Poster img")?.attr("src")
        val plot = document.selectFirst("div.Story")?.text()?.trim()
        
        // Extract details from the list
        val detailsList = document.select("div.Details ul li")
        val year = detailsList.find { it.text().contains("سنة الانتاج") }
            ?.text()?.replace("سنة الانتاج", "")?.trim()?.toIntOrNull()
        val tags = detailsList.find { it.text().contains("القسم") }
            ?.select("a")?.map { it.text() }
        val rating = detailsList.find { it.text().contains("التقييم") }
            ?.text()?.replace("التقييم", "")?.trim()
            ?.let { it.toFloatOrNull()?.times(100)?.toInt() }
        val duration = detailsList.find { it.text().contains("وقت الحلقة") }
            ?.text()?.replace("وقت الحلقة", "")?.trim()
            ?.filter { it.isDigit() }?.toIntOrNull()

        // Check if it's a TV series by looking for seasons list
        val seasonsList = document.select("div.List--Seasons--Episodes")
        val isTvSeries = seasonsList.isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // Loop through each season tab
            document.select("ul.List--Seasons li a").forEach { seasonTab ->
                val seasonName = seasonTab.text()
                val seasonNum = seasonName.filter { it.isDigit() }.toIntOrNull()
                val seasonId = seasonTab.attr("data-season")

                // Find the corresponding episodes list for the season
                document.select("div#$seasonId ul.hoverable-list li a").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epTitle = epElement.text()
                    val epNum = epTitle.filter { it.isDigit() }.toIntOrNull()
                    
                    episodes.add(newEpisode(epHref) {
                        name = epTitle
                        season = seasonNum
                        episode = epNum
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating
                // duration is per episode, so we don't set it for the whole series
            }
        } else {
            // It's a Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating
                this.duration = duration
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
