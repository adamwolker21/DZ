package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val posterImage = document.selectFirst("div.single-thumbnail img")
        val posterUrl = posterImage?.attr("src")
        
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
        if(plotAppendix.isNotEmpty()) {
            plot = "$plot<br><br>$plotAppendix"
        }

        val categoryText = document.selectFirst("li:has(span:contains(القسم)) a")?.text() ?: ""
        val isSeries = categoryText.contains("مسلسلات")

        if (isSeries) {
            val seriesTitle = pageTitle
                .replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "")
                .trim()

            // In this stable version, we only load episodes if they are directly on the page
            val episodes = document.select("div.EpsList li a").mapNotNull { epElement ->
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
                this.duration = duration
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
        // First, get the initial page to acquire cookies
        val initialResponse = app.get(data)
        var document = initialResponse.document
        
        // If the server list isn't present, perform the POST request to reveal it
        if (document.select("div.servers-list iframe").isEmpty()) {
            val cookies = initialResponse.cookies
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to data,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Origin" to mainUrl,
                "sec-fetch-dest" to "document",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "same-origin",
                "sec-fetch-user" to "?1"
            )
            val postData = mapOf("View" to "1")
            document = app.post(data, headers = headers, data = postData, cookies = cookies).document
        }

        // Now, extract the iframe links from the (potentially new) document
        document.select("div.servers-list iframe").apmap {
            val link = it.attr("src")
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
