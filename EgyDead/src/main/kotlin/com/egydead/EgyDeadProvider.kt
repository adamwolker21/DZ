package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    // Add CloudflareKiller interceptor to handle potential Cloudflare protection
    private val interceptor = CloudflareKiller()

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

        val document = app.get(url, interceptor = interceptor).document
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
        val document = app.get(searchUrl, interceptor = interceptor).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val initialDoc = app.get(url, interceptor = interceptor).document

        val watchButton = initialDoc.selectFirst("button input[name=View]")
        val document = if (watchButton != null) {
            val headers = mapOf(
                "Referer" to url,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )
            app.post(url, data = mapOf("View" to "1"), headers = headers, interceptor = interceptor).document
        } else {
            initialDoc
        }
        
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterImage = document.selectFirst("div.single-thumbnail img")
        val posterUrl = posterImage?.attr("src")
        val altTitle = posterImage?.attr("alt")

        var plot = document.selectFirst("div.extra-content p")?.text()?.trim()

        val infoList = document.select("div.single-content > ul > li")
        val year = infoList.find { it.text().contains("السنه") }
            ?.selectFirst("a")?.text()?.toIntOrNull()
        val tags = infoList.find { it.text().contains("النوع") }
            ?.select("a")?.map { it.text() }
        val duration = infoList.find { it.text().contains("مده العرض") }
            ?.selectFirst("a")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val country = infoList.find { it.text().contains("البلد") }
            ?.selectFirst("a")?.text()

        if (country != null) {
            plot = "البلد: $country\n\n$plot"
        }

        val episodesList = document.select("div.EpsList li a")
        if (episodesList.isNotEmpty()) {
            val seriesTitle = (altTitle ?: pageTitle)
                .replace("مشاهدة", "")
                .replace(Regex("""(فيلم|مسلسل|مترجم|كامل|الحلقة \d+)"""), "")
                .trim()

            val episodes = episodesList.mapNotNull { epElement ->
                val epHref = epElement.attr("href")
                val epTitleAttr = epElement.attr("title")
                val epNum = epTitleAttr.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()

                newEpisode(epHref) {
                    name = epElement.text().trim()
                    episode = epNum
                    season = 1
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val movieTitle = pageTitle
                .replace("مشاهدة", "")
                .replace("فيلم", "")
                .replace("مترجم", "")
                .trim()

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
        // To be implemented
        return false
    }
}
