package com.5ive.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.ArrayList

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
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
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

        // --- الميزة الأهم: إعادة التوجيه لصفحة المسلسل إذا كنا داخل حلقة ---
        // نبحث عن زر "قائمه الحلقات والمواسم"
        val backToSeriesLink = document.selectFirst("a:has(span:contains(قائمه الحلقات))")?.attr("href")
        
        if (!backToSeriesLink.isNullOrEmpty() && (currentUrl.contains("/episode/") || currentUrl.contains("حلقة"))) {
            // تحديث الرابط والمستند ليكون الخاص بالمسلسل بدلاً من الحلقة
            currentUrl = backToSeriesLink
            document = app.get(currentUrl).document
        }

        // استخراج البيانات الأساسية
        // تم تحديث المحددات لتشمل meta tags لأنها أدق وتعمل دائماً
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("مشاهدة مسلسل", "")?.replace("مترجم اون لاين - فايف تي في", "")?.replace("مشاهدة فيلم", "")?.trim() 
            ?: document.selectFirst(".ftvx-title-main h1, h1")?.text() ?: ""
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // استخراج القصة (الوصف)
        val plot = document.selectFirst("meta[name=description]")?.attr("content") 
            ?: document.selectFirst(".story-content, .ftvx-story")?.text()
        
        // استخراج السنة
        val yearText = document.selectFirst(".ftvx-chip")?.text() ?: document.selectFirst(".card-year")?.text()
        val year = yearText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        // استخراج التصنيفات
        val tags = document.select(".ftvx-cats a").map { it.text() }

        // استخراج الحلقات
        val episodesElements = document.select(".modern-episodes-grid a.modern-episode-card")

        if (episodesElements.isNotEmpty()) {
            val episodes = episodesElements.mapNotNull { epLink ->
                val epUrl = epLink.attr("href") ?: return@mapNotNull null
                val epTitle = epLink.selectFirst(".modern-badge")?.text() ?: ""
                val epPosterElement = epLink.selectFirst("img")
                val epPoster = epPosterElement?.attr("data-src")?.ifEmpty { epPosterElement.attr("src") }
                
                // استخراج رقم الموسم والحلقة من الرابط بذكاء
                // الروابط في الموقع تأتي بصيغة: name-1x2 (الموسم 1 الحلقة 2)
                val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)").find(epUrl)
                
                val seasonNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull() 
                    ?: epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()

                Episode(
                    data = epUrl,
                    name = epTitle,
                    season = seasonNum,
                    episode = episodeNum,
                    posterUrl = epPoster
                )
            }

            return newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
            }
        } else {
            // إذا لم توجد حلقات فهو فيلم
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
        
        // 1. البحث عن مشغلات الفيديو المضمنة مباشرة (iframes)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 2. محاولة جلب روابط التحميل المباشرة إن وجدت في الـ HTML (كما في ملف الفيلم الذي أرسلته)
        document.select(".ftv-download-item a.ftv-download-button").forEach { downloadLink ->
            val href = downloadLink.attr("href")
            val serverName = downloadLink.parent()?.selectFirst(".ftv-download-info strong")?.text() ?: "تحميل"
            val quality = downloadLink.parent()?.selectFirst(".ftv-quality-chip .ftv-chip-value")?.text() ?: ""
            val name = "$serverName $quality".trim()

            if (href.isNotEmpty()) {
                // قد تكون روابط التحميل تحتاج لتخطي، لكننا نرسلها كـ ExtractorLink لاختبارها
                callback.invoke(
                    ExtractorLink(
                        source = "FiveTV Download",
                        name = name,
                        url = href,
                        referer = mainUrl,
                        quality = getQualityFromName(quality),
                        isM3u8 = href.contains(".m3u8")
                    )
                )
                foundLinks = true
            }
        }

        return foundLinks
    }

    // دالة مساعدة لتحويل النص إلى صيغة جودة يفهمها التطبيق
    private fun getQualityFromName(qualityName: String): Int {
        return when {
            qualityName.contains("1080") -> Qualities.P1080.value
            qualityName.contains("720") -> Qualities.P720.value
            qualityName.contains("480") -> Qualities.P480.value
            qualityName.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
