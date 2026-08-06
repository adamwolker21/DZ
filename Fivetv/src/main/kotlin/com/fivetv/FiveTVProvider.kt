package com.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class FiveTVProvider : MainAPI() {
    override var mainUrl = "https://5tv.lol"
    override var name = "FiveTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/asian/" to "المسلسلات الآسيوية",
        "$mainUrl/category/korean-rows/" to "الدراما الكورية",
        "$mainUrl/country/united-states-of-america/" to "الأفلام الأجنبية",
        "$mainUrl/category/new-rows/" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val home = document.select("li[class*=-publish] article.card-modern, .card-modern").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = this.selectFirst(".card-title")?.text()?.trim() ?: return null
        
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgElement?.attr("src")

        val isSeries = href.contains("/series/") || href.contains("مسلسل")

        return if (isSeries) {
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("li[class*=-publish] article.card-modern, .card-modern").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var currentUrl = url
        var document = app.get(currentUrl).document

        // التوجيه الذكي من صفحة الحلقة إلى صفحة المسلسل الأصلية
        val backToSeriesLink = document.selectFirst("a:has(span:contains(قائمه الحلقات))")?.attr("href")
        
        if (!backToSeriesLink.isNullOrBlank() && (currentUrl.contains("/episode/") || currentUrl.contains("حلقة"))) {
            currentUrl = backToSeriesLink
            document = app.get(currentUrl).document
        }

        // جلب العنوان (دعم الأفلام والمسلسلات ftvx و ftvm)
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace("مشاهدة مسلسل", "")
            ?.replace("مترجم اون لاين - فايف تي في", "")
            ?.replace("مشاهدة فيلم", "")
            ?.trim() 
            ?: document.selectFirst(".ftvx-title-main h1, .ftvm-title-main h1, h1")?.text()?.trim() ?: return null
            
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // جلب القصة بدقة للأفلام (ftvm) والمسلسلات (ftvx)
        val plot = document.selectFirst(".ftvx-description-inner p, .ftvm-description-inner p, .ftvx-description-inner, .ftvm-description-inner")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
        
        // جلب سنة الإنتاج
        val yearText = document.selectFirst(".ftvx-chip, .ftvm-chip")?.text() ?: document.selectFirst(".card-year")?.text()
        val year = yearText?.filter { it.isDigit() }?.toIntOrNull()
        
        // جلب المدة الزمنية بذكاء
        var duration: Int? = null
        val durationText = document.select(".ftvm-chip, .ftvx-chip").find { 
            it.text().contains("m") || it.text().contains("h") || it.text().contains("دقيقة") 
        }?.text()

        if (durationText != null) {
            if (durationText.contains("دقيقة")) {
                duration = durationText.filter { it.isDigit() }.toIntOrNull()
            } else {
                val hours = Regex("(\\d+)h").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("(\\d+)m").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val totalMinutes = (hours * 60) + minutes
                if (totalMinutes > 0) duration = totalMinutes
            }
        }

        // جلب التصنيفات (دعم الأفلام والمسلسلات)
        val tags = document.select(".ftvx-cats a, .ftvm-cats a").map { it.text() }

        val episodesElements = document.select(".modern-episodes-grid a.modern-episode-card")
        val isSeries = episodesElements.isNotEmpty() || currentUrl.contains("/series/") || currentUrl.contains("مسلسل")

        if (isSeries) {
            val episodes = episodesElements.mapNotNull { epLink ->
                val epUrl = epLink.attr("href")
                if (epUrl.isNullOrBlank()) return@mapNotNull null

                val epTitle = epLink.selectFirst(".modern-badge")?.text()?.trim() ?: ""
                
                val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)").find(epUrl)
                val seasonNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull()
                val episodeNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull() 
                    ?: epTitle.filter { it.isDigit() }.toIntOrNull()

                val epPosterElement = epLink.selectFirst("img")
                val epPosterUrl = epPosterElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: epPosterElement?.attr("src")

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = epPosterUrl
                }
            }

            return newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = plot
                this.duration = duration
            }
        } else {
            return newMovieLoadResponse(title, currentUrl, TvType.Movie, currentUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = plot
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
        val iframes = document.select("iframe")
        
        iframes.amap { iframe ->
            try {
                val src = iframe.attr("data-src").takeIf { it.isNotBlank() } ?: iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("FiveTVProvider", "Failed to load extractor: ${e.message}")
            }
        }
        
        return true
    }
}
