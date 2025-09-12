// v14: The final, modern provider code compatible with the new build environment.
package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else request.data
        val document = app.get(url).document
        val allhome = mutableListOf<HomePageList>()

        if (page > 1) {
            val items = document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
            return newHomePageResponse(request.name, items, true)
        }
        
        document.select("div.Blocks").forEach { section ->
            val title = section.selectFirst("div.title-bar h2")?.text() ?: return@forEach
            val items = section.select("div.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                allhome.add(HomePageList(title, items))
            }
        }
        return HomePageResponse(allhome)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val posterDiv = this.selectFirst("div.poster") ?: return null
        val link = posterDiv.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("div.data h2 a")?.text() ?: return null
        val posterUrl = posterDiv.selectFirst("img")?.attr("data-src") ?: posterDiv.selectFirst("img")?.attr("src")

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href) {
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
        val rating = document.selectFirst("div.imdb span")?.text()?.toRatingInt()
        val recommendations = document.select("div.related div.item").mapNotNull {
            it.toSearchResponse()
        }

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
            }
        } else {
            val episodes = mutableListOf<Episode>()
            document.select("div#seasons div.season_item").forEachIndexed { seasonIndex, seasonElement ->
                val seasonNum = seasonElement.selectFirst("h3")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (seasonIndex + 1)
                seasonElement.select("ul.episodes li").forEach { episodeElement ->
                    val epLink = episodeElement.selectFirst("a") ?: return@forEach
                    val epHref = fixUrl(epLink.attr("href"))
                    val epName = epLink.text()
                    val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("div.servers-list iframe")
        
        coroutineScope {
            iframes.map { iframe ->
                async {
                    val iframeSrc = fixUrl(iframe.attr("src"))
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }.awaitAll()
        }
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
    }
}
