package com.5ive.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FiveTVProvider : MainAPI() {
    override var mainUrl = "https://5tv.lol"
    override var name = "FiveTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/asian/" to "المسلسلات الآسيوية",
        "$mainUrl/category/korean-rows/" to "الدراما الكورية",
        "$mainUrl/country/united-states-of-america/" to "الأفلام الأجنبية",
        "$mainUrl/category/new-rows/" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("li[class*=-publish] article.card-modern, .card-modern").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".card-title")?.text() ?: return null
        val url = this.selectFirst("a")?.attr("href") ?: return null
        
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") }
        val year = this.selectFirst(".card-year")?.text()?.toIntOrNull()

        val isSeries = url.contains("/series/") || url.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("li[class*=-publish] article.card-modern, .card-modern").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var currentUrl = url
        var document = app.get(currentUrl).document

        // إعادة التوجيه لصفحة المسلسل إذا كنا داخل حلقة
        val backToSeriesLink = document.selectFirst("a:has(span:contains(قائمه الحلقات))")?.attr("href")
        
        if (!backToSeriesLink.isNullOrBlank() && (currentUrl.contains("/episode/") || currentUrl.contains("حلقة"))) {
            currentUrl = backToSeriesLink
            document = app.get(currentUrl).document
        }

        // استخراج البيانات الأساسية
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace("مشاهدة مسلسل", "")
            ?.replace("مترجم اون لاين - فايف تي في", "")
            ?.replace("مشاهدة فيلم", "")
            ?.trim() 
            ?: document.selectFirst(".ftvx-title-main h1, h1")?.text() ?: ""
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plot = document.selectFirst("meta[name=description]")?.attr("content") 
            ?: document.selectFirst(".story-content, .ftvx-story")?.text()
        
        val yearText = document.selectFirst(".ftvx-chip")?.text() ?: document.selectFirst(".card-year")?.text()
        val year = yearText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        val tags = document.select(".ftvx-cats a").map { it.text() }

        val episodesElements = document.select(".modern-episodes-grid a.modern-episode-card")

        if (episodesElements.isNotEmpty()) {
            val episodes = episodesElements.mapNotNull { epLink ->
                val epUrl = epLink.attr("href") ?: return@mapNotNull null
                val epTitle = epLink.selectFirst(".modern-badge")?.text() ?: ""
                val epPosterElement = epLink.selectFirst("img")
                val epPoster = epPosterElement?.attr("data-src")?.ifBlank { epPosterElement.attr("src") }
                
                val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)").find(epUrl)
                val seasonNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull() 
                    ?: epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()

                // استخدام دالة newEpisode بدلاً من Episode لحل مشكلة البناء (Deprecated)
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = epPoster
                }
            }

            return newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, currentUrl, TvType.Movie, currentUrl) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
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
        var foundLinks = false
        
        // البحث عن مشغلات الفيديو المضمنة مباشرة (iframes)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        // تم تجاهل أزرار التحميل اليدوية لأنها غالباً روابط توجيهية (Redirect)
        // وليست روابط MP4 مباشرة، ومحاولة جلبها كانت تسبب أخطاء Qualities.

        return foundLinks
    }
}
