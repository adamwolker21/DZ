package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class FaselHDSProvider : MainAPI() {
    // تم تغيير الرابط الأساسي للرابط المباشر لتخطي خطأ التوجيه (Redirect)
    override var mainUrl = "https://web850x.faselhdx.bid"
    override var name = "FaselHDS"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "/movies" to "أفلام أجنبي",
        "/asian-movies" to "أفلام آسيوي",
        "/series" to "جميع المسلسلات",
        "/recent_series" to "أحدث المسلسلات",
        "/episodes" to "احدث الحلقات",
        "/asian-episodes" to "أحدث الحلقات الآسيوية",
        "/recent_asian" to "المضاف حديثا آسيوي",
        "/asian-series" to "جميع المسلسلات الآسيوية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}" + (if (page > 1) "/page/$page" else "")
        val document = app.get(url, headers = headers).document
        
        val selector = if (document.selectFirst("div.post-listing") != null) {
            "div.post-listing div.postDiv"
        } else {
            "div.postDiv"
        }

        val home = document.select(selector).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val title = anchor.selectFirst("div.h1")?.text()?.trim() ?: "No Title"
        
        val posterElement = this.selectFirst("div.imgdiv-class img, a > img.img-fluid, img.poster")
        val posterUrl = posterElement?.attr("data-src") ?: posterElement?.attr("src")
        
        val isSeries = href.contains("/series/") || href.contains("/asian-series/") || title.contains("مسلسل")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document
        return document.select("div.postDiv").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.h1.title, h1.title")?.ownText()?.trim() ?: "No Title"
        
        var posterUrl = document.selectFirst("div.posterImg img, img.poster")?.attr("src")
        if (posterUrl.isNullOrBlank()) {
            val seasonListPoster = document.selectFirst("div#seasonList img")
            posterUrl = seasonListPoster?.attr("data-src") ?: seasonListPoster?.attr("src")
        }

        val plot = document.selectFirst("div.singleDesc p, div.singleDesc")?.text()?.trim()
        val tags = document.select("div.col-xl-6:contains(تصنيف) a, div.tags a").map { it.text() }
        val year = document.select("span:contains(سنة الإنتاج) a, span:contains(موعد الصدور)").text().let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        val isSingleEpisode = url.contains("/episodes/") || url.contains("episode")
        val isTvSeries = (url.contains("/series/") || url.contains("/asian-series/") || document.select("div#seasonList, div#epAll, div.seasonDiv").isNotEmpty()) && !isSingleEpisode

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            val episodeSelector = "div#epAll a, div#episodes a, div.ep-item a, div.episodes-list a"

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    val onClick = seasonElement.attr("onclick")
                    val seasonUrl = Regex("""['"](https?://[^'"]+|/[^'"]+)['"]""").find(onClick)?.groupValues?.get(1) 
                        ?: seasonElement.selectFirst("a")?.attr("href")
                        
                    if (!seasonUrl.isNullOrBlank()) {
                        val absSeasonUrl = if (seasonUrl.startsWith("http")) seasonUrl else "$mainUrl$seasonUrl"
                        val seasonNum = Regex("""\d+""").find(seasonElement.text())?.value?.toIntOrNull() ?: 1
                        
                        try {
                            val seasonDoc = app.get(absSeasonUrl, headers = headers).document
                            seasonDoc.select(episodeSelector).forEach { ep ->
                                val epName = ep.text().trim()
                                episodes.add(newEpisode(ep.attr("href")) {
                                    this.name = epName
                                    this.season = seasonNum
                                    this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                                })
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
            
            if (episodes.isEmpty()) {
                document.select(episodeSelector).forEach { ep ->
                    val epName = ep.text().trim()
                    episodes.add(newEpisode(ep.attr("href")) {
                        this.name = epName
                        this.season = 1
                        this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                    })
                }
            }

            if (episodes.isEmpty()) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        document.select("ul.tabs-ul li, div.servers-list li").forEachIndexed { index, serverElement ->
            val serverUrl = Regex("""['"](https?://[^'"]+)['"]""").find(serverElement.attr("onclick"))?.groupValues?.get(1)
                ?: serverElement.attr("data-url")
            
            if (serverUrl.isBlank()) return@forEachIndexed

            try {
                val fixedUrl = if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl
                
                // استخراج نطاق المشغل لعمل هيدرز خاصة به (مهم جداً لتخطي الحماية)
                val playerDomain = "https://${URI(fixedUrl).host}"
                val playerHeaders = mapOf(
                    "User-Agent" to headers["User-Agent"]!!,
                    "Referer" to "$playerDomain/",
                    "Origin" to playerDomain
                )
                
                val playerDoc = app.get(fixedUrl, headers = playerHeaders).document
                val playerHtml = playerDoc.html()
                
                var foundLink: String? = null
                
                val regexes = listOf(
                    Regex("""(https?://[^"']+\.m3u8[^"']*)"""),
                    Regex("""(https?://[^"']+\.mp4[^"']*)"""),
                    Regex("""file\s*:\s*["']([^"']+)["']"""),
                    Regex("""source\s*:\s*["']([^"']+)["']""")
                )

                for (regex in regexes) {
                    val match = regex.find(playerHtml)
                    if (match != null) {
                        foundLink = match.groupValues[1]
                        break
                    }
                }

                if (!foundLink.isNullOrBlank()) {
                    val isM3u8Url = foundLink.contains(".m3u8", ignoreCase = true)
                    
                    if (isM3u8Url) {
                        M3u8Helper.generateM3u8(
                            source = "$name - Server ${index + 1}",
                            streamUrl = foundLink,
                            referer = "$playerDomain/",
                            headers = playerHeaders
                        ).forEach(callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - Server ${index + 1}",
                                name = "$name - Server ${index + 1}",
                                url = foundLink
                            ) {
                                this.referer = "$playerDomain/"
                                this.quality = Qualities.Unknown.value
                                this.headers = playerHeaders
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // تخطي في حال تعطل أحد السيرفرات
            }
        }
        return true
    }
}
