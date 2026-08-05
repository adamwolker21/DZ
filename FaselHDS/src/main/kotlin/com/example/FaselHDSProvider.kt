package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
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
        val title = anchor.selectFirst("div.h1")?.text() ?: "No Title"
        
        val posterElement = this.selectFirst("div.imgdiv-class img, a > img.img-fluid")
        val posterUrl = posterElement?.attr("data-src") 
            ?: posterElement?.attr("src")
        
        val isSeries = title.contains("مسلسل") || title.contains("برنامج") ||
                       this.selectFirst("span.quality:contains(حلقة), span.quality:contains(مواسم)") != null
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select("div.postDiv").mapNotNull {
            it.toSearchResult()
        }
    }
    
    private fun Element.getMetaInfo(iconClass: String): String? {
        return this.selectFirst("span:has(i.$iconClass)")?.ownText()?.substringAfter(":")?.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.h1.title")?.ownText()?.trim() ?: "No Title"
        
        var posterUrl = document.selectFirst("div.posterImg img")?.attr("src")
        if (posterUrl.isNullOrBlank()) {
            val seasonListPoster = document.selectFirst("div#seasonList img")
            posterUrl = seasonListPoster?.attr("data-src") ?: seasonListPoster?.attr("src")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("img.poster")?.attr("src")
        }

        var plot = document.selectFirst("div.singleDesc p")?.text()?.trim()
            ?: document.selectFirst("div.singleDesc")?.text()?.trim()

        val tags = document.select("div.col-xl-6:contains(تصنيف) a").map { it.text() }
        
        val isTvSeries = document.select("div#seasonList, div#epAll").isNotEmpty()

        if (isTvSeries) {
            val year = Regex("""\d{4}""").find(document.select("span:contains(موعد الصدور)").firstOrNull()?.text() ?: "")?.value?.toIntOrNull()
            var status: ShowStatus? = null
            val statusText = document.selectFirst("span:contains(حالة المسلسل)")?.text() ?: ""
            if (statusText.contains("مستمر")) {
                status = ShowStatus.Ongoing
            } else if (statusText.contains("مكتمل")) {
                status = ShowStatus.Completed
            }
            
            val duration = document.getMetaInfo("fa-clock")?.filter { it.isDigit() }?.toIntOrNull()
            
            val country = document.getMetaInfo("fa-flag")
            val episodeCount = document.getMetaInfo("fa-film")

            val infoList = mutableListOf<String>()
            episodeCount?.let { infoList.add("<b>الحلقات:</b> $it") }
            country?.let { infoList.add("<b>دولة المسلسل:</b> $it") }
            
            if (infoList.isNotEmpty()) {
                plot += "<br><br>${infoList.joinToString(" | ")}"
            }

            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            val episodeSelector = "div#epAll a, div#episodes a, div.ep-item a"

            if (seasonElements.isNotEmpty()) {
                seasonElements.amap { seasonElement ->
                    val seasonLink = seasonElement.attr("onclick")?.substringAfter("'")?.substringBefore("'")
                        ?: seasonElement.selectFirst("a")?.attr("href") ?: return@amap
                    val absoluteSeasonLink = if (seasonLink.startsWith("http")) seasonLink else "$mainUrl$seasonLink"
                    val seasonNum = Regex("""\d+""").find(seasonElement.selectFirst("div.title")?.text() ?: "")?.value?.toIntOrNull()
                    val seasonDoc = app.get(absoluteSeasonLink, headers = headers).document
                    
                    seasonDoc.select(episodeSelector).forEach { ep ->
                        episodes.add(
                            newEpisode(ep.attr("href")) {
                                name = ep.text().trim()
                                season = seasonNum
                                episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                            }
                        )
                    }
                }
            } else {
                document.select(episodeSelector).forEach { ep ->
                    episodes.add(
                        newEpisode(ep.attr("href")) {
                            name = ep.text().trim()
                            season = 1
                            episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                this.duration = duration
            }
        } else { // It's a Movie
            val year = document.selectFirst("span:contains(سنة الإنتاج) a")?.text()?.toIntOrNull()
            val duration = document.getMetaInfo("fa-clock")?.filter { it.isDigit() }?.toIntOrNull()
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags; this.duration = duration
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
        
        document.select("ul.tabs-ul li").forEachIndexed { index, serverElement ->
            val onClickAttr = serverElement.attr("onclick") ?: return@forEachIndexed
            val serverUrl = onClickAttr.substringAfter("href = '", "").substringBefore("'", "")
            
            if (serverUrl.isBlank()) return@forEachIndexed

            try {
                // إصلاح الرابط في حال كان يبدأ بـ // بدلاً من https://
                val fixedUrl = if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl
                
                // جلب صفحة المشغل مع تمرير الرابط الأصلي كـ Referer لتخطي بعض الحمايات
                val playerDoc = app.get(fixedUrl, headers = mapOf("Referer" to data, "User-Agent" to headers["User-Agent"]!!)).document
                val playerHtml = playerDoc.html()
                
                var foundLink: String? = null
                
                // 1. المحاولة الأولى: البحث عن الرابط داخل علامات <source> أو <video>
                val sourceSrc = playerDoc.selectFirst("source")?.attr("src") ?: playerDoc.selectFirst("video")?.attr("src")
                if (!sourceSrc.isNullOrBlank()) {
                    foundLink = sourceSrc
                }
                
                // 2. المحاولة الثانية: استخدام تعبيرات منتظمة متقدمة للبحث عن متغيرات الجافاسكريبت داخل صفحة المشغل
                if (foundLink.isNullOrBlank()) {
                    val linkRegexes = listOf(
                        Regex("""file\s*:\s*["'](https?://[^"']+m3u8[^"']*)["']"""),
                        Regex("""file\s*:\s*["'](https?://[^"']+mp4[^"']*)["']"""),
                        Regex("""var\s+videoSrc\s*=\s*["']([^"']+)["']"""),
                        Regex("""source\s*:\s*["']([^"']+)["']"""),
                        Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                    )

                    for (regex in linkRegexes) {
                        val match = regex.find(playerHtml)
                        if (match != null) {
                            foundLink = match.groupValues[1]
                            break
                        }
                    }
                }

                // 3. إذا تم العثور على الرابط، نقوم بإرجاعه بناءً على نوعه
                if (!foundLink.isNullOrBlank()) {
                    val isM3u8 = foundLink.contains(".m3u8", ignoreCase = true)
                    
                    if (isM3u8) {
                        M3u8Helper.generateM3u8(
                            source = "$name - Server ${index + 1}",
                            streamUrl = foundLink,
                            referer = fixedUrl,
                            headers = mapOf("Referer" to fixedUrl, "Origin" to fixedUrl.substringBefore("/video_player"))
                        ).forEach(callback)
                    } else {
                        // تم التعديل هنا لاستخدام newExtractorLink بدلاً من ExtractorLink
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - Server ${index + 1}",
                                name = "$name - Server ${index + 1}",
                                url = foundLink,
                                referer = fixedUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // تخطي الأخطاء الخاصة بسيرفر معين لكي لا تتوقف باقي السيرفرات
            }
        }
        return true
    }
}
