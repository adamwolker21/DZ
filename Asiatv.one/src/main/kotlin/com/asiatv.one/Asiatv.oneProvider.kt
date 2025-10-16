package com.asiatv.one

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AsiatvoneProvider : MainAPI() {
    // The base URL remains the same, /home/ is just a path
    override var mainUrl = "https://asiatv.one"
    override var name = "AsiaTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // Common headers to mimic a browser, based on your cURL info
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // Updated main page sections
    override val mainPage = mainPageOf(
        "$mainUrl/دراما-تبث-حاليا/" to "دراما تبث حاليا",
        "$mainUrl/types/الدراما-الكورية/" to "الدراما الكورية",
        "$mainUrl/types/الدراما-الصينية/" to "الدراما الصينية",
        "$mainUrl/types/الدراما-اليابانية/" to "الدراما اليابانية",
        "$mainUrl/دراما-مكتملة/" to "دراما مكتملة",
        "$mainUrl/types/افلام-اسيوية/" to "افلام اسيوية",
    )

    // Fetch and parse main page sections with new selectors
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        // Using headers in the request
        val document = app.get(url, headers = commonHeaders).document

        // Updated selector to find content items
        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Updated helper function to parse items based on the new HTML structure
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        // The title attribute on the <a> tag is more reliable
        val title = linkElement.attr("title") ?: return null
        // The poster is in the 'src' attribute of the img
        val posterUrl = this.selectFirst("img.imgLoaded")?.attr("src")

        // Distinguish between series and movies based on URL structure
        // /drama/ or /series/ are common for TV shows
        val isSeries = href.contains("/drama/") || href.contains("/series/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Handle search queries with new selectors
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl, headers = commonHeaders).document

        // Use the same updated selector for search results
        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    // Load series/movie details and episodes list (selectors for this page seem unchanged for now)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.name")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster > img")?.attr("src")
        val plot = document.selectFirst("div.story")?.text()?.trim()

        val isSeries = document.select("ul.episodes-list").isNotEmpty()

        return if (isSeries) {
            val episodes = document.select("ul.episodes-list > li").mapNotNull {
                val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                newEpisode(epUrl) {
                    this.name = it.selectFirst("a")?.text()?.trim()
                }
            }.reversed()

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

    // Load video links (selectors for this page seem unchanged for now)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = commonHeaders).document
        var linksLoaded = false
        document.select("div.servers-list > ul > li").forEach { serverElement ->
            val serverUrl = serverElement.attr("data-server")
            if (serverUrl.isNotBlank()) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)?.let {
                    linksLoaded = true
                }
            }
        }
        return linksLoaded
    }
}
