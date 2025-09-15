package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.getQualityFromName

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
    
    // تحسين: جعل Headers قابلة للوصول في كل مكان
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
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
        if (href.isBlank()) return null
        val title = anchor.selectFirst("div.h1, h3 a")?.text() ?: "No Title"
        val posterElement = anchor.selectFirst("div.imgdiv-class img, div.post-thumb img")
        val posterUrl = posterElement?.attr("data-src") 
            ?: posterElement?.attr("src")
            ?: anchor.selectFirst("div.post-thumb a")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
        
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

        val title = document.selectFirst("div.h1.title")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.posterImg img")?.attr("src")
            ?: document.selectFirst("img.poster")?.attr("src")
        val plot = document.selectFirst("div.singleDesc p")?.text()?.trim()
            ?: document.selectFirst("div.singleDesc")?.text()?.trim()
        val yearText = document.select("span:contains(موعد الصدور)").firstOrNull()?.text()
        val year = Regex("""\d{4}""").find(yearText ?: "")?.value?.toIntOrNull()
        
        val tags = document.select("span:contains(تصنيف) a").map { it.text() }
        
        val isTvSeries = url.contains("/series/") || document.select("div#seasonList").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            
            if (seasonElements.isNotEmpty()) {
                seasonElements.apmap { seasonElement ->
                    val seasonLink = seasonElement.attr("onclick")?.substringAfter("window.location.href = '")?.substringBefore("'")
                        ?: seasonElement.selectFirst("a")?.attr("href")
                    val seasonNumText = seasonElement.selectFirst("div.title")?.text()
                    val seasonNum = Regex("""\d+""").find(seasonNumText ?: "")?.value?.toIntOrNull()

                    if (seasonLink != null) {
                        val seasonDoc = app.get(seasonLink, headers = headers).document
                        seasonDoc.select("div.ep-item a").forEach { episodeLink ->
                            val epHref = episodeLink.attr("href")
                            val epTitle = episodeLink.selectFirst(".eph-num")?.text() ?: "الحلقة"
                            val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

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
                document.select("div.ep-item a").forEach { episodeLink ->
                     val epHref = episodeLink.attr("href")
                     val epTitle = episodeLink.selectFirst(".eph-num")?.text() ?: "الحلقة"
                     val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            // تعديل مهم: بالنسبة للأفلام، نمرر رابط الفيلم نفسه إلى loadLinks
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String, // هنا `data` هو رابط الحلقة أو الفيلم
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // الخطوة 1: جلب محتوى صفحة الفيلم أو الحلقة
        val document = app.get(data, headers = headers).document

        // الخطوة 2: استخراج جميع روابط السيرفرات (صفحات المشغل)
        val serverUrls = document.select("ul.tabs-ul li").mapNotNull {
            it.attr("onclick").substringAfter("href = '").substringBefore("'")
        }

        if (serverUrls.isEmpty()) return false

        // الخطوة 3: المرور على كل رابط سيرفر واستخراج الفيديو منه
        serverUrls.apmap { serverUrl ->
            try {
                val playerPageContent = app.get(serverUrl, headers = headers).text
                
                // البحث بالطريقة الأولى (hlsPlaylist & data-url)
                val hlsJson = Regex("""var hlsPlaylist = (\{.+?});""").find(playerPageContent)?.groupValues?.get(1)
                if (hlsJson != null) {
                    val fileLink = Regex(""""file":"([^"]+)"""").find(hlsJson)?.groupValues?.get(1)
                    if(fileLink != null) {
                         callback.invoke(
                            ExtractorLink(
                                this.name,
                                "${this.name} - Auto",
                                fileLink,
                                serverUrl,
                                Qualities.Unknown.value,
                                fileLink.contains(".m3u8")
                            )
                        )
                    }
                // الطريقة الثانية (videoSrc)
                } else {
                    val videoSrc = Regex("""var videoSrc = '([^']+)';""").find(playerPageContent)?.groupValues?.get(1)
                    if(videoSrc != null) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                this.name,
                                videoSrc,
                                serverUrl,
                                Qualities.Unknown.value,
                                videoSrc.contains(".m3u8")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // تجاهل الخطأ في حالة فشل أحد السيرفرات
            }
        }

        return true
    }
}

