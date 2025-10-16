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

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%aa%d8%a8%d8%ab-%d8%ad%d8%a7%d9%84%d9%8a%d8%a7/" to "دراما تبث حاليا",
        "$mainUrl/types/الدراما-الكورية/" to "الدراما الكورية",
        "$mainUrl/types/الدراما-الصينية/" to "الدراما الصينية",
        "$mainUrl/types/الدراما-اليابانية/" to "الدراما اليابانية",
        "$mainUrl/%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d9%85%d9%83%d8%aa%d9%85%d9%84%d8%a9/" to "دراما مكتملة",
        "$mainUrl/types/افلام-اسيوية/" to "افلام اسيوية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = commonHeaders).document

        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Updated to prioritize data-img for posters
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.attr("title") ?: return null
        
        // Prioritize 'data-img' and fallback to 'src'
        val imageElement = this.selectFirst("img.imgLoaded")
        val posterUrl = imageElement?.attr("data-img")?.ifBlank {
            imageElement.attr("src")
        }

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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl, headers = commonHeaders).document
        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    // Updated with all new selectors for the load function
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        // New selectors for title, poster, plot, and tags
        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val plot = document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("div.single_tax a").map { it.text() }

        // Logic to check if it's a series remains the same
        val episodesList = document.select("ul.episodes-list > li")
        val isSeries = episodesList.isNotEmpty()

        return if (isSeries) {
            val episodes = episodesList.mapNotNull {
                val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                newEpisode(epUrl) {
                    this.name = it.selectFirst("a")?.text()?.trim()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

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
