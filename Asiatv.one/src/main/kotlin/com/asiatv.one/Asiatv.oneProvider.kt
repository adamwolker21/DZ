package com.asiatv.one

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern
import android.util.Log

class AsiatvoneProvider : MainAPI() {
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
        "$mainUrl/الحلقات-الجديدة/" to "الحلقات الجديدة",
        "$mainUrl/دراما-تبث-حاليا/" to "دراما تبث حاليا",
        "$mainUrl/دراما-مكتملة/" to "دراما مكتملة",
        "$mainUrl/types/الدراما-الكورية/" to "الدراما الكورية",
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
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logTag = "AsiaTVLogs"
        var linksLoaded = false

        try {
            Log.d(logTag, "Starting loadLinks for: $data")

            val episodePage = app.get(data, headers = commonHeaders).document
            val epwatch = episodePage.selectFirst("input[name=epwatch]")?.attr("value")
            
            if (epwatch.isNullOrBlank()) {
                Log.e(logTag, "Could not find 'epwatch' ID.")
                return false
            }
            Log.d(logTag, "Target epwatch: $epwatch")

            val watchUrl = "https://asiawiki.me/"
            
            // 1. إرسال الطلب الأولي وتخزين الكوكيز تلقائياً في OkHttp
            val watchResponse = app.post(
                watchUrl,
                data = mapOf("epwatch" to epwatch),
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to data,
                    "User-Agent" to USER_AGENT
                ),
                allowRedirects = true
            )
            val newReferer = watchResponse.url

            // 2. السر الأكبر: طلب الـ AJAX يجب أن يكون بصيغة GET !
            val ajaxUrl = "https://asiawiki.me/wp-admin/admin-ajax.php?action=fetch_episode&id=$epwatch"
            
            val ajaxResponseText = app.get(
                ajaxUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Origin" to "https://asiawiki.me",
                    "Referer" to newReferer,
                    "User-Agent" to USER_AGENT
                )
            ).text

            Log.d(logTag, "AJAX Response Length: ${ajaxResponseText.length}")

            // 3. التنظيف العنيف لفك أي تشفير سواء كان JSON أو Unicode أو HTML Entities
            val cleanHtml = ajaxResponseText
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\n", "")
                .replace("\\r", "")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0022", "\"")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#038;", "&")
                .replace("&amp;", "&")

            // 4. استخراج كافة روابط الفيديو بقوة الـ Regex
            val srcRegex = """src=["'](.*?)["']""".toRegex(RegexOption.IGNORE_CASE)
            val extractedUrls = srcRegex.findAll(cleanHtml).map { it.groupValues[1] }.toList()

            Log.d(logTag, "Found ${extractedUrls.size} potential links.")

            extractedUrls.distinct().amap { rawUrl ->
                try {
                    // استبعاد الصور أو الملفات غير المرغوبة
                    if (rawUrl.endsWith(".jpg") || rawUrl.endsWith(".png") || rawUrl.contains("google.com")) return@amap
                    
                    var embedUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                    
                    // استبدال النطاقات الوهمية بالنطاقات التي تفهمها إضافة Cloudstream
                    when {
                        embedUrl.contains("playmogo.com") -> embedUrl = embedUrl.replace("playmogo.com", "dood.to")
                        embedUrl.contains("vidmoly.biz") -> embedUrl = embedUrl.replace("vidmoly.biz", "vidmoly.to")
                        embedUrl.contains("voe.sx") -> embedUrl = embedUrl.replace("voe.sx", "voe.unblocked.lol")
                        embedUrl.contains("bysefujedu.com") -> embedUrl = embedUrl.replace("bysefujedu.com", "filemoon.sx")
                        embedUrl.contains("vinovo.to") -> embedUrl = embedUrl.replace("vinovo.to", "vidmoly.to")
                        embedUrl.contains("luluvdo.com") -> embedUrl = embedUrl.replace("luluvdo.com", "lulustream.com")
                    }
                    
                    Log.d(logTag, "Loading extractor for: $embedUrl")
                    
                    loadExtractor(embedUrl, newReferer, subtitleCallback, callback)?.let {
                        linksLoaded = true
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error processing URL ($rawUrl): ${e.message}")
                }
            }

        } catch (e: Throwable) {
            Log.e(logTag, "Crash Prevented: ${e.message}")
            e.printStackTrace()
        }

        return linksLoaded
    }
}
