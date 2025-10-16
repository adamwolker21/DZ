package com.asiatv.one

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.regex.Pattern

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
        "$mainUrl/دراما-تبث-حاليا/" to "دراما تبث حاليا",
        "$mainUrl/types/الدراما-الكورية/" to "الدراما الكورية",
        "$mainUrl/types/الدراما-الصينية/" to "الدراما الصينية",
        "$mainUrl/types/الدراما-اليابانية/" to "الدراما اليابانية",
        "$mainUrl/دراما-مكتملة/" to "دراما مكتملة",
        "$mainUrl/types/افلام-اسيوية/" to "افلام اسيوية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = commonHeaders).document
        
        // Select both types of articles to handle different page layouts
        val home = document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.attr("title") ?: return null

        val posterUrl = if (this.hasClass("postEp")) {
            // Handle the structure for "دراما مكتملة" sections
            this.selectFirst("div.imgSer")?.attr("data-img")
        } else {
            // Handle the default structure
            val imageElement = this.selectFirst("img.imgLoaded")
            imageElement?.attr("data-img")?.ifBlank {
                imageElement.attr("src")
            }
        }

        // Differentiate based on title content for main pages
        val isMovie = title.contains("فيلم")

        return if (!isMovie) {
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
        // Search results use the default layout
        return document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val plot = document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("div.single_tax a[rel=tag]").map { it.text() }
        
        // Extract the year from the broadcast date
        var year: Int? = null
        document.select("div.single_tax span").forEach { span ->
            if (span.text().contains("مواعيد البث")) {
                val dateText = span.nextElementSibling()?.text()
                if (dateText != null) {
                    val pattern = Pattern.compile("(\\d{4})")
                    val matcher = pattern.matcher(dateText)
                    if (matcher.find()) {
                        year = matcher.group(1)?.toIntOrNull()
                    }
                }
            }
        }

        // More robust movie/series detection
        val isMovie: Boolean
        val episodeCountText = document.select("div.single_tax span").find { it.text().contains("عدد الحلقات") }?.nextElementSibling()?.text()
        val firstEpText = document.selectFirst("ul.eplist2 li a")?.text()

        when {
            episodeCountText?.contains("فيلم") == true -> isMovie = true
            firstEpText?.contains("فيلم") == true -> isMovie = true
            title.contains("فيلم") -> isMovie = true
            else -> isMovie = false
        }

        return if (!isMovie) {
            // Handle new and old episode list structures
            val episodes = (document.select("ul.eplist2 > li") + document.select("ul.episodes-list > li")).mapNotNull {
                val link = it.selectFirst("a") ?: return@mapNotNull null
                val epUrl = link.attr("href")
                val epName = link.attr("title").ifBlank { link.text() }
                
                newEpisode(epUrl) {
                    this.name = epName.trim()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
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
        // Assuming servers list structure is consistent
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
