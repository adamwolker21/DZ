package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
// We are now using the helper function
import com.egydead.EgyDeadUtils.getWatchPageData

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
        val document = app.get(searchUrl).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        var plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val durationText = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()
        val duration = durationText?.filter { it.isDigit() }?.toIntOrNull()
        val country = document.selectFirst("li:has(span:contains(البلد)) a")?.text()
        val channel = document.select("li:has(span:contains(القناه)) a").joinToString(", ") { it.text() }

        var plotAppendix = ""
        if (!country.isNullOrBlank()) {
            plotAppendix += "البلد: $country"
        }
        if (channel.isNotBlank()) {
            if (plotAppendix.isNotEmpty()) plotAppendix += " | "
            plotAppendix += "القناه: $channel"
        }
        if (duration != null) {
            if (plotAppendix.isNotEmpty()) plotAppendix += " | "
            plotAppendix += "المدة: $duration دقيقة"
        }
        if(plotAppendix.isNotEmpty()) {
            plot = "$plot<br><br>$plotAppendix"
        }

        val categoryText = document.selectFirst("li:has(span:contains(القسم)) a")?.text() ?: ""
        val isSeries = categoryText.contains("مسلسلات")

        if (isSeries) {
             var episodes = document.select("div.EpsList li a").mapNotNull { epElement ->
                val href = epElement.attr("href")
                val titleAttr = epElement.attr("title")
                val epNum = titleAttr.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()
                if (epNum == null) return@mapNotNull null
                newEpisode(href) {
                    this.name = epElement.text().trim()
                    this.episode = epNum
                    this.season = 1
                }
            }.toMutableList()

            if (document.selectFirst("div.watchNow form") != null) {
                val watchPageData = getWatchPageData(url)
                episodes = watchPageData?.episodes?.toMutableList() ?: mutableListOf()
            }
            
            val seriesTitle = pageTitle
                .replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "")
                .trim()
            
            val currentEpNum = pageTitle.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()
            if (currentEpNum != null && episodes.none { it.episode == currentEpNum }) {
                 episodes.add(newEpisode(url) {
                    this.name = pageTitle.substringAfter(seriesTitle).trim().ifBlank { "حلقه $currentEpNum" }
                    this.episode = currentEpNum
                    this.season = 1
                })
            }
            
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageData = getWatchPageData(data)
        val serverLinks = watchPageData?.serverLinks ?: emptyList()

        serverLinks.apmap { link ->
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
