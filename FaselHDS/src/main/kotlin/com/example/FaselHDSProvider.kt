package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class FaselHDSProvider : MainAPI() {
    override var mainUrl = "https://www.faselhds.life"
    override var name = "FaselHDS"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // دالة ديناميكية لإنشاء الهيدرز بناءً على النطاق الفعلي بعد التوجيه
    private fun getDynHeaders(currentUrl: String): Map<String, String> {
        val domain = try {
            val uri = URI(currentUrl)
            "https://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to "$domain/",
            "Origin" to domain
        )
    }

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
        val document = app.get(url, headers = getDynHeaders(url)).document
        
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
        
        val posterElement = this.selectFirst("div.imgdiv-class img, a > img.img-fluid")
        val posterUrl = posterElement?.attr("data-src") ?: posterElement?.attr("src")
        
        // التحقق الدقيق من نوع المحتوى
        val isSeries = href.contains("/series/") || href.contains("/asian-series/") || title.contains("مسلسل")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = getDynHeaders(url)).document
        return document.select("div.postDiv").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val dynHeaders = getDynHeaders(url)
        val document = app.get(url, headers = dynHeaders).document
        val title = document.selectFirst("div.h1.title, h1.title")?.ownText()?.trim() ?: "No Title"
        
        var posterUrl = document.selectFirst("div.posterImg img, img.poster")?.attr("src")
        if (posterUrl.isNullOrBlank()) {
            val seasonListPoster = document.selectFirst("div#seasonList img")
            posterUrl = seasonListPoster?.attr("data-src") ?: seasonListPoster?.attr("src")
        }

        val plot = document.selectFirst("div.singleDesc p, div.singleDesc")?.text()?.trim()
        val tags = document.select("div.col-xl-6:contains(تصنيف) a, div.tags a").map { it.text() }
        val year = document.select("span:contains(سنة الإنتاج) a, span:contains(موعد الصدور)").text().let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        // تحديد إذا ما كانت الصفحة هي لمسلسل يحتوي على حلقات، أم أنها حلقة/فيلم مفرد
        val isSingleEpisode = url.contains("/episodes/") || url.contains("episode")
        val isTvSeries = (url.contains("/series/") || url.contains("/asian-series/") || document.select("div#seasonList, div#epAll, div.seasonDiv").isNotEmpty()) && !isSingleEpisode

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            val episodeSelector = "div#epAll a, div#episodes a, div.ep-item a, div.episodes-list a"

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    // استخراج الرابط من الخصائص المتعددة المحتملة بذكاء
                    val onClick = seasonElement.attr("onclick")
                    val seasonUrl = Regex("""['"](https?://[^'"]+|/[^'"]+)['"]""").find(onClick)?.groupValues?.get(1) 
                        ?: seasonElement.selectFirst("a")?.attr("href")
                        
                    if (!seasonUrl.isNullOrBlank()) {
                        val absSeasonUrl = if (seasonUrl.startsWith("http")) seasonUrl else "${dynHeaders["Origin"]}$seasonUrl"
                        val seasonNum = Regex("""\d+""").find(seasonElement.text())?.value?.toIntOrNull() ?: 1
                        
                        try {
                            val seasonDoc = app.get(absSeasonUrl, headers = getDynHeaders(absSeasonUrl)).document
                            seasonDoc.select(episodeSelector).forEach { ep ->
                                val epName = ep.text().trim()
                                episodes.add(newEpisode(ep.attr("href")) {
                                    this.name = epName
                                    this.season = seasonNum
                                    this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                                })
                            }
                        } catch (e: Exception) {
                            // تخطي خطأ الموسم لإكمال المواسم الأخرى
                        }
                    }
                }
            }
            
            // في حال لم تكن مقسمة لمواسم بل قائمة حلقات مباشرة
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

            // إذا فشل في العثور على أي حلقة، نعيده كفيلم لكي لا تتوقف الواجهة
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
        val dynHeaders = getDynHeaders(data)
        val document = app.get(data, headers = dynHeaders).document
        
        document.select("ul.tabs-ul li, div.servers-list li").forEachIndexed { index, serverElement ->
            val serverUrl = Regex("""['"](https?://[^'"]+)['"]""").find(serverElement.attr("onclick"))?.groupValues?.get(1)
                ?: serverElement.attr("data-url")
            
            if (serverUrl.isBlank()) return@forEachIndexed

            try {
                val fixedUrl = if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl
                val playerHeaders = getDynHeaders(fixedUrl) // استخراج دومين المشغل
                
                val playerDoc = app.get(fixedUrl, headers = mapOf("Referer" to data, "User-Agent" to playerHeaders["User-Agent"]!!)).document
                val playerHtml = playerDoc.html()
                
                var foundLink: String? = null
                
                // البحث العنيف عن الروابط
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
                        // استخدام الهيدرز المطابقة تماماً لـ curl
                        M3u8Helper.generateM3u8(
                            source = "$name - Server ${index + 1}",
                            streamUrl = foundLink,
                            referer = playerHeaders["Origin"]!! + "/",
                            headers = mapOf(
                                "Origin" to playerHeaders["Origin"]!!,
                                "Referer" to playerHeaders["Origin"]!! + "/",
                                "Accept" to "*/*",
                                "User-Agent" to playerHeaders["User-Agent"]!!
                            )
                        ).forEach(callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - Server ${index + 1}",
                                name = "$name - Server ${index + 1}",
                                url = foundLink
                            ) {
                                this.referer = playerHeaders["Origin"]!! + "/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Origin" to playerHeaders["Origin"]!!,
                                    "Referer" to playerHeaders["Origin"]!! + "/",
                                    "Accept" to "*/*",
                                    "User-Agent" to playerHeaders["User-Agent"]!!
                                )
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // تخطي خطأ السيرفر
            }
        }
        return true
    }
}
