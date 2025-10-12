package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log 
import android.util.Base64 // v7 Change: Import Base64 for decoding

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

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = cloudflareKiller)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl,
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-user" to "?1"
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = headers, data = data, cookies = cookies, interceptor = cloudflareKiller).document
            }
            return document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }

        val document = app.get(url, interceptor = cloudflareKiller).document
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
        val document = app.get(searchUrl, interceptor = cloudflareKiller).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val duration = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val categoryText = document.selectFirst("li:has(span:contains(القسم)) a")?.text() ?: ""
        val isSeries = categoryText.contains("مسلسلات") || pageTitle.contains("مسلسل") || pageTitle.contains("الموسم") || document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodesDoc = getWatchPage(url) ?: document

            val episodes = episodesDoc.select("div.EpsList li a").mapNotNull { epElement ->
                val href = epElement.attr("href")
                val titleAttr = epElement.attr("title")
                val epNum = titleAttr.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
                if (epNum == null) return@mapNotNull null
                newEpisode(href) {
                    this.name = epElement.text().trim()
                    this.episode = epNum
                }
            }.distinctBy { it.episode }.toMutableList()
            
            val seriesTitle = pageTitle
                .replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "")
                .trim()
            
            val currentEpNum = pageTitle.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
            if (currentEpNum != null && episodes.none { it.episode == currentEpNum }) {
                 episodes.add(newEpisode(url) {
                    this.name = pageTitle.substringAfter(seriesTitle).trim().ifBlank { "حلقة $currentEpNum" }
                    this.episode = currentEpNum
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
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("EgyDeadProvider", "loadLinks invoked for URL: $data")
        val watchPageDoc = getWatchPage(data)
        
        if (watchPageDoc == null) {
            Log.e("EgyDeadProvider", "Failed to get watch page document.")
            return false
        }
        
        Log.d("EgyDeadProvider", "Successfully got watch page document. Title: ${watchPageDoc.title()}")

        val servers = watchPageDoc.select("div.mob-servers li")
        
        Log.d("EgyDeadProvider", "Found ${servers.size} potential server elements.")

        servers.apmap { serverLi ->
            var link = serverLi.attr("data-link")
            
            if (link.isBlank()) {
                val serverText = serverLi.text()
                Log.d("EgyDeadProvider", "data-link is blank. Checking server text: '$serverText'")

                if (serverText.contains("EarnVids", ignoreCase = true) || serverLi.selectFirst("img")?.attr("alt")?.contains("EarnVids", ignoreCase = true) == true) {
                    val onclickAttr = serverLi.attr("onclick")
                    Log.d("EgyDeadProvider", "Found EarnVids server. Found onclick attribute: $onclickAttr")
                    
                    // v7 Change: New regex to capture the Base64 content from GoTo('...')
                    val base64Regex = Regex("""GoTo\('([^']+)""")
                    val base64Url = base64Regex.find(onclickAttr)?.groupValues?.get(1)

                    if (base64Url != null) {
                        // v7 Change: Decode the Base64 string to get the real URL
                        link = try {
                            String(Base64.decode(base64Url, Base64.DEFAULT))
                        } catch (e: Exception) {
                            Log.e("EgyDeadProvider", "Base64 decoding failed for: $base64Url")
                            "" // Return empty string on failure
                        }
                        Log.d("EgyDeadProvider", "Decoded URL from Base64: $link")
                    }
                }
            } else {
                 Log.d("EgyDeadProvider", "Found server link from data-link attribute: $link")
            }

            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
