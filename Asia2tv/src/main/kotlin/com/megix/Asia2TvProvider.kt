package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Asia2TvProvider : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else request.data
        val document = app.get(url).document

        // التعامل مع الصفحات التالية للتصنيفات (عند الضغط على "المزيد")
        if (page > 1) {
            val items = document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
            // v2 Update: The second argument to newHomePageResponse is the list itself.
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }

        // التعامل مع الصفحة الرئيسية
        val allhome = document.select("div.Blocks").mapNotNull { section ->
            val title = section.selectFirst("div.title-bar h2")?.text() ?: return@mapNotNull null
            val categoryUrl = section.selectFirst("div.title-bar a.more")?.attr("href") ?: return@mapNotNull null
            val items = section.select("div.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                // v2 Update: 'isHorizontal' and 'url' parameters are removed from HomePageList constructor.
                // The "view more" link is now passed as `data` in the main request.
                // We pass the categoryUrl here so the framework can use it for the next page.
                HomePageList(title, items, data = categoryUrl)
            } else {
                null
            }
        }
        return newHomePageResponse(allhome, hasNext = true)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val posterDiv = this.selectFirst("div.poster") ?: return null
        val link = posterDiv.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("div.data h2 a")?.text() ?: return null
        val posterUrl = posterDiv.selectFirst("img")?.attr("data-src") ?: posterDiv.selectFirst("img")?.attr("src")

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.story p")?.text()?.trim()
        val year = document.select("div.meta span a[href*=release]").first()?.text()?.toIntOrNull()
        val tags = document.select("div.meta span a[href*=genre]").map { it.text() }
        val ratingValue = document.selectFirst("div.imdb span")?.text()?.let {
            (it.toFloatOrNull()?.times(100))?.toInt()
        }
        // v2 Update: The 'score' property now requires a Score object, not an Int.
        val rating = ratingValue?.let { Score(it, 1000) }

        val recommendations = document.select("div.related div.item").mapNotNull {
            it.toSearchResponse()
        }

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = rating
                this.recommendations = recommendations
            }
        } else {
            val episodes = document.select("div#seasons div.season_item").flatMapIndexed { seasonIndex, seasonElement ->
                val seasonNum = seasonElement.selectFirst("h3")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (seasonIndex + 1)
                seasonElement.select("ul.episodes li").mapNotNull { episodeElement ->
                    val epLink = episodeElement.selectFirst("a") ?: return@mapNotNull null
                    val epHref = fixUrl(epLink.attr("href"))
                    val epName = epLink.text()
                    val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                    newEpisode(epHref) {
                        name = epName
                        season = seasonNum
                        episode = epNum
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = rating
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        // v2 Update: 'apmap' has been removed or is no longer accessible. Switched to a sequential 'forEach'.
        document.select("div.servers-list iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
    }
}
