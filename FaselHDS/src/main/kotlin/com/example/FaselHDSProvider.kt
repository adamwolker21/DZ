package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/movies" to "أحدث الأفلام",
        "/series" to "أحدث المسلسلات",
        "/genre/افلام-انمي" to "أفلام أنمي",
        "/genre/افلام-اسيوية" to "أفلام أسيوية",
        "/genre/افلام-تركية" to "أفلام تركية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}/page/$page"
        val document = app.get(url, headers = headers).document
        val home = document.select("div.itemviews div.postDiv, div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href")
        val title = anchor.selectFirst("div.h1, h3 a")?.text() ?: "No Title"
        val posterElement = anchor.selectFirst("div.imgdiv-class img, div.post-thumb img")
        val posterUrl = posterElement?.attr("data-src") 
            ?: posterElement?.attr("src")
            ?: anchor.selectFirst("div.post-thumb a")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
        
        // تحديد النوع بناءً على الرابط والمحتوى
        val isSeries = href.contains("/series/") || this.selectFirst("span.quality:contains(حلقة)") != null
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
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
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document

        return document.select("div.itemviews div.postDiv, div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        // استخراج العنوان
        val title = document.selectFirst("div.h1.title")?.text()?.trim() ?: "No Title"
        
        // استخراج الصورة
        val posterUrl = document.selectFirst("div.posterImg img")?.attr("src")
            ?: document.selectFirst("img.poster")?.attr("src")
        
        // استخراج القصة/الوصف
        val plot = document.selectFirst("div.singleDesc p")?.text()?.trim()
            ?: document.selectFirst("div.singleDesc")?.text()?.trim()
        
        // استخراج السنة
        var year: Int? = null
        val yearText = document.select("span:contains(موعد الصدور)").firstOrNull()?.text()
        year = Regex("""موعد الصدور : (\d{4})""").find(yearText ?: "")?.groupValues?.get(1)?.toIntOrNull()
        
        // استخراج التصنيفات
        val tags = mutableListOf<String>()
        document.select("span:contains(تصنيف المسلسل) a[rel=tag]").forEach {
            tags.add(it.text())
        }
        
        // تحديد إذا كان مسلسلاً
        val isTvSeries = document.select("div#seasonList").isNotEmpty() 
            || url.contains("/series/")
            || document.select("span:contains(حلقة)").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // استخراج المواسم
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            
            if (seasonElements.isNotEmpty()) {
                // هناك مواسم متعددة
                seasonElements.forEach { seasonElement ->
                    val seasonLink = seasonElement.attr("onclick")?.substringAfter("window.location.href = '")?.substringBefore("'")
                        ?: seasonElement.selectFirst("a")?.attr("href")
                    val seasonUrl = if (seasonLink?.startsWith("/") == true) "$mainUrl$seasonLink" else seasonLink
                    val seasonNumText = seasonElement.selectFirst("div.title")?.text()
                    val seasonNum = Regex("""موسم (\d+)""").find(seasonNumText ?: "")?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1

                    if (seasonUrl != null) {
                        val seasonDoc = app.get(seasonUrl, headers = headers).document
                        
                        seasonDoc.select("a:contains(الحلقة)").forEach { episodeLink ->
                            val epHref = episodeLink.attr("href")
                            val epTitle = episodeLink.text()
                            val epNum = Regex("""الحلقة (\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                            episodes.add(
                                newEpisode(epHref) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                }
            } else {
                // موسم واحد فقط - استخراج الحلقات مباشرة من الصفحة
                document.select("a:contains(الحلقة)").forEach { episodeLink ->
                    val epHref = episodeLink.attr("href")
                    val epTitle = episodeLink.text()
                    val epNum = Regex("""الحلقة (\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // فيلم - استخراج أزرار المشاهدة
            val watchLinks = document.select("a.watch-btn, ul.quality-list li a").map {
                val embedUrl = it.attr("data-url") ?: it.attr("href")
                val name = it.text()

                newEpisode(embedUrl) {
                    this.name = name
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, watchLinks) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // أولاً: محاولة استخراج الفيديو مباشرة من الصفحة
            val embedPage = app.get(data, referer = "$mainUrl/", headers = headers).document
            
            // البحث عن رابط فيديو مباشر (HLS)
            val videoElement = embedPage.selectFirst("video#video")
            val videoSrc = videoElement?.attr("src")
            
            if (!videoSrc.isNullOrEmpty() && videoSrc.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "FaselHDS - HLS",
                        videoSrc,
                        "$mainUrl/",
                        1080, // جودة افتراضية
                        true
                    )
                )
                return true
            }
            
            // إذا لم يتم العثور على فيديو مباشر، البحث عن iframe
            val iframeElement = embedPage.selectFirst("iframe")
            val iframeSrc = iframeElement?.attr("src")
            
            if (iframeSrc != null) {
                if (iframeSrc.contains(".m3u8")) {
                    // إذا كان رابط iframe مباشرةً إلى ملف HLS
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "FaselHDS - HLS",
                            iframeSrc,
                            "$mainUrl/",
                            1080,
                            true
                        )
                    )
                    return true
                } else {
                    // إذا كان iframe يؤدي إلى صفحة أخرى، تحميلها واستخراج الفيديو منها
                    val iframePage = app.get(iframeSrc, referer = data, headers = headers).document
                    val iframeVideo = iframePage.selectFirst("video#video")
                    val iframeVideoSrc = iframeVideo?.attr("src")
                    
                    if (!iframeVideoSrc.isNullOrEmpty() && iframeVideoSrc.contains(".m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "FaselHDS - HLS",
                                iframeVideoSrc,
                                iframeSrc,
                                1080,
                                true
                            )
                        )
                        return true
                    }
                    
                    // إذا لم يتم العثور على فيديو في iframe، استخدام extractors العادية
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    return true
                }
            }
            
            // إذا لم يتم العثور على أي فيديو أو iframe
            return false
        } catch (e: Exception) {
            // في حالة حدوث خطأ، استخدام الطريقة التقليدية
            val embedPage = app.get(data, referer = "$mainUrl/", headers = headers).document
            val iframeSrc = embedPage.selectFirst("iframe")?.attr("src") ?: return false
            loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
            return true
        }
    }
}
