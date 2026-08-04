package com.asiatv.one

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern
import android.util.Log

class AsiatvoneProvider : MainAPI() {
    // إزالة السلاش من النهاية لمنع مشكلة // في الروابط
    override var mainUrl = "https://asiatvdrama.com" 
    override var name = "AsiaTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a7%d8%aa-%d8%a7%d9%84%d8%ac%d8%af%d9%8a%d8%af%d8%a9/" to "الحلقات الجديدة",
        "$mainUrl/%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%aa%d8%a8%d8%ab-%d8%ad%d8%a7%d9%84%d9%8a%d8%a7/" to "دراما تبث حاليا",
        "$mainUrl/%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d9%85%d9%83%d8%aa%d9%85%d9%84%d8%a9/" to "دراما مكتملة",
        "$mainUrl/types/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d9%83%d9%88%d8%b1%d9%8a%d8%a9/" to "الدراما الكورية",
        "$mainUrl/types/الدراما-الصينية/" to "الدراما الصينية",
        "$mainUrl/types/الدراما-اليابانية/" to "الدراما اليابانية",
        "$mainUrl/types/افلام-اسيوية/" to "افلام اسيوية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = commonHeaders).document
        
        val home = document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.attr("title") ?: return null

        val posterUrl = if (this.hasClass("postEp")) {
            this.selectFirst("div.imgSer")?.attr("data-img")
        } else {
            val imageElement = this.selectFirst("img.imgLoaded")
            imageElement?.attr("data-img")?.ifBlank {
                imageElement.attr("src")
            }
        }

        val isMovie = title.contains("فيلم")

        return if (!isMovie) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl, headers = commonHeaders).document
        return document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        
        val posterElement = document.selectFirst("div.poster-wrapper img, div.poster img")
        val poster = posterElement?.attr("data-lazy-src")?.ifBlank {
            posterElement.attr("src")
        }
        
        var plot = document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("div.single_tax a[rel=tag]").map { it.text() }
        
        var year: Int? = null
        document.select("div.single_tax span").forEach { span ->
            if (span.text().contains("مواعيد البث")) {
                val dateText = span.nextElementSibling()?.text()
                if (dateText != null) {
                    val pattern = Pattern.compile("(\\d{4})")
                    val matcher = pattern.matcher(dateText)
                    if (matcher.find()) {
                        year = matcher.group(1)?.toIntOrNull()
                    }
                }
            }
        }

        val actors = document.select("div.single-team ul.team li").mapNotNull {
            val name = it.selectFirst("div > span")?.text() ?: return@mapNotNull null
            val imageElement = it.selectFirst("img")
            val image = imageElement?.attr("data-lazy-src")?.ifBlank {
                imageElement.attr("src")
            }
            ActorData(Actor(name, image))
        }

        val episodeCountSpan = document.select("div.single_tax span").find { it.text().contains("عدد الحلقات") }
        val episodeCountText = episodeCountSpan?.nextElementSibling()?.text()
        val firstEpText = document.selectFirst("ul.eplist2 li a")?.text()

        val isMovie = when {
            episodeCountText?.contains("فيلم") == true -> true
            firstEpText?.contains("فيلم") == true -> true
            title.contains("فيلم") -> true
            else -> false
        }
        
        if (!isMovie && !episodeCountText.isNullOrBlank()) {
            plot += "<br><br>عدد الحلقات: $episodeCountText"
        }

        return if (!isMovie) {
            val episodes = (document.select("ul.eplist2 > li") + document.select("ul.episodes-list > li")).mapNotNull {
                val link = it.selectFirst("a") ?: return@mapNotNull null
                val epUrl = link.attr("href")
                val epName = link.attr("title").ifBlank { link.text() }
                
                newEpisode(epUrl) {
                    this.name = epName.trim()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String, // Episode page URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logTag = "AsiaTVLogs"
        Log.d(logTag, "loadLinks started for: $data")

        // 1. جلب رقم الحلقة (epwatch)
        val episodePage = app.get(data, headers = commonHeaders).document
        val epwatch = episodePage.selectFirst("input[name=epwatch]")?.attr("value")
        if (epwatch.isNullOrBlank()) {
            Log.e(logTag, "Failed to find 'epwatch' value.")
            return false
        }
        Log.d(logTag, "Found 'epwatch' value: $epwatch")

        val postHeaders = mapOf(
            "authority" to "asiawiki.me",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Origin" to mainUrl,
            "Referer" to data,
            "User-Agent" to USER_AGENT
        )
        
        // 2. إرسال طلب POST بدون توجيه تلقائي لالتقاط الرابط الجديد والكوكيز (الطريقة المعقدة والمضمونة)
        val initialResponse = app.post(
            "https://asiawiki.me/",
            data = mapOf("epwatch" to epwatch),
            allowRedirects = false,
            headers = postHeaders
        )

        val watchPageUrl = initialResponse.headers["location"] ?: initialResponse.headers["Location"]
        val cookies = initialResponse.headers["set-cookie"] ?: initialResponse.headers["Set-Cookie"]

        if (watchPageUrl.isNullOrBlank() || cookies.isNullOrBlank()) {
            Log.e(logTag, "Failed to get redirect URL or Cookies. Status: ${initialResponse.code}")
            return false
        }
        Log.d(logTag, "Got redirect URL: $watchPageUrl")
        Log.d(logTag, "Got Cookies: $cookies")

        // 3. الذهاب للرابط الجديد مع إرفاق الكوكيز
        val finalHeaders = mapOf(
            "Referer" to data,
            "Cookie" to cookies,
            "User-Agent" to USER_AGENT
        )
        val watchPageDocument = app.get(watchPageUrl, headers = finalHeaders).document
        Log.d(logTag, "Successfully fetched watch page content.")
        
        var linksLoaded = false
        
        // 4. استخراج السيرفرات باستخدام Regex لضمان الدقة
        watchPageDocument.select("ul.ServerNames li").amap { serverElement ->
            try {
                val iframeHtml = serverElement.attr("data-server")
                
                // استخراج رابط src من كود الإطار المدمج
                val srcRegex = """src=["'](.*?)["']""".toRegex(RegexOption.IGNORE_CASE)
                val embedUrlRaw = srcRegex.find(iframeHtml)?.groupValues?.get(1) 
                    ?: Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src")
                
                if (!embedUrlRaw.isNullOrBlank()) {
                    // إصلاح الروابط التي تبدأ بـ // لتجنب الأخطاء
                    val embedUrl = if (embedUrlRaw.startsWith("//")) "https:$embedUrlRaw" else embedUrlRaw
                    
                    Log.d(logTag, "Found embed URL: $embedUrl")
                    loadExtractor(embedUrl, watchPageUrl, subtitleCallback, callback)?.let {
                        linksLoaded = true
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error parsing server: ${e.message}")
            }
        }

        if (!linksLoaded) {
            Log.e(logTag, "No links were loaded from any server.")
        }
        
        return linksLoaded
    }
}


