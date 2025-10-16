package com.asiatv.one

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AsiatvoneProvider : MainAPI() {
    override var mainUrl = "https://asiatv.one"
    override var name = "AsiaTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // Define sections for the main page
    override val mainPage = mainPageOf(
        "$mainUrl/category/k-drama/completed-k-drama/" to "دراما كورية مكتملة",
        "$mainUrl/category/k-drama/ongoing-k-drama/" to "دراما كورية مستمرة",
        "$mainUrl/category/asian-drama-movies/" to "أفلام آسيوية",
        "$mainUrl/category/j-drama/" to "دراما يابانية",
        "$mainUrl/category/c-drama/" to "دراما صينية",
    )

    // Fetch and parse main page sections
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        val home = document.select("article.post-listing").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Helper function to parse search result items from HTML element
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.post-card__title a")?.text() ?: return null
        val href = this.selectFirst("h2.post-card__title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst(".post-card__image img")?.attr("data-src")

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Handle search queries
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl).document
        return document.select("article.post-listing").mapNotNull {
            it.toSearchResult()
        }
    }

    // Load series/movie details and episodes list
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.name")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster > img")?.attr("src")
        val plot = document.selectFirst("div.story")?.text()?.trim()

        val episodes = document.select("ul.episodes-list > li").mapNotNull {
            val epName = it.selectFirst("a")?.text()?.trim() ?: return@mapNotNull null
            val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            Episode(
                data = epUrl,
                name = epName,
            )
        }.reversed()

        return if (url.contains("/series/")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // Load video links from an episode page
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // This function will require HTML from an episode page to implement correctly.
        // We need to find the iframe or video player source to extract video links.
        // For now, it's a placeholder.
        // Example:
        // val document = app.get(data).document
        // val iframeSrc = document.selectFirst("iframe")?.attr("src")
        // if(iframeSrc != null) {
        //     loadExtractor(iframeSrc, subtitleCallback, callback)
        // }
        return false
    }
}
