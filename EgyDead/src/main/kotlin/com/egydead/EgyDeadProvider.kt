package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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

        val isSeries = title.contains("مسلسل") || title.contains("الموسم") || href.contains("/series/")

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
        val initialResponse = app.get(url)
        var document = initialResponse.document

        // إرسال طلب POST مع البيانات المطلوبة
        val cookies = initialResponse.cookies
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to url,
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "max-age=0",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Upgrade-Insecure-Requests" to "1"
        )

        // إرسال طلب POST مع البيانات الصحيحة
        val data = mapOf(
            "View" to "1"
        )

        document = app.post(url, headers = headers, data = data, cookies = cookies).document

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
        val isSeries = categoryText.contains("مسلسلات") || url.contains("/series/") || document.select("div.EpsList li a").isNotEmpty()

        if (isSeries) {
            val seriesTitle = pageTitle
                .replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة|مشاهدة)"""), "")
                .trim()

            val episodes = document.select("div.EpsList li a").mapNotNull { epElement ->
                val epHref = epElement.attr("href")
                val epText = epElement.text().trim()
                val epTitleAttr = epElement.attr("title")
                
                // استخراج رقم الحلقة من النص
                val epNum = when {
                    epText.contains(Regex("""الحلقة\s*(\d+)""")) -> {
                        Regex("""الحلقة\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    epTitleAttr.contains(Regex("""الحلقة\s*(\d+)""")) -> {
                        Regex("""الحلقة\s*(\d+)""").find(epTitleAttr)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    else -> {
                        // محاولة استخراج الرقم من النص مباشرة
                        Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                    }
                }

                newEpisode(epHref) {
                    name = epElement.text().trim()
                    episode = epNum ?: 1
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
        val document = app.get(data).document

        // البحث عن iframes أو مصادر الفيديو
        val iframes = document.select("iframe[src]")
        
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                // إذا كان الرابط يحتوي على فيديو مباشر
                if (src.contains(".mp4") || src.contains(".m3u8") || src.contains("youtube") || src.contains("stream")) {
                    callback(
                        ExtractorLink(
                            name = "EgyDead",
                            source = src,
                            url = src,
                            quality = Qualities.Unknown.value,
                            referer = "$mainUrl/"
                        )
                    )
                }
            }
        }

        // البحث عن روابط الفيديو في scripts
        val scriptTexts = document.select("script").map { it.html() }
        scriptTexts.forEach { script ->
            // البحث عن روابط m3u8
            val m3u8Links = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(script)
            m3u8Links.forEach { match ->
                callback(
                    ExtractorLink(
                        name = "EgyDead",
                        source = match.value,
                        url = match.value,
                        quality = Qualities.Unknown.value,
                        referer = "$mainUrl/"
                    )
                )
            }

            // البحث عن روابط mp4
            val mp4Links = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").findAll(script)
            mp4Links.forEach { match ->
                callback(
                    ExtractorLink(
                        name = "EgyDead",
                        source = match.value,
                        url = match.value,
                        quality = Qualities.Unknown.value,
                        referer = "$mainUrl/"
                    )
                )
            }
        }

        return iframes.isNotEmpty() || scriptTexts.isNotEmpty()
    }
}
