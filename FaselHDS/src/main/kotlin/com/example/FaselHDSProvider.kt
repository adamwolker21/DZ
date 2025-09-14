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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
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

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.poster img, img.poster")?.attr("src")
            ?: document.selectFirst("img[itemprop=image]")?.attr("src")
        val plot = document.selectFirst("div.entry-content p, div.desc p")?.text()?.trim()
            ?: document.selectFirst("div.entry-content, div.desc")?.text()?.trim()
        
        // استخراج السنة
        var year: Int? = null
        document.select("span.year, div.meta-bar:contains(سنة)").forEach { element ->
            val text = element.text()
            year = text.toIntOrNull() 
                ?: Regex("""(\d{4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // استخراج التصنيفات
        val tags = document.select("span.cat, a[rel=tag]").map { it.text() }
        
        // تحديد إذا كان مسلسلاً
        val isTvSeries = document.select("div#season-list, div.season-list, div.ep-list").isNotEmpty() 
            || url.contains("/series/")
            || document.select("span:contains(حلقة)").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // محاولة استخراج المواسم والحلقات
            val seasonElements = document.select("div.season-list-item, div.season-item")
            
            if (seasonElements.isNotEmpty()) {
                // هناك مواسم متعددة
                seasonElements.forEach { seasonElement ->
                    val seasonLink = seasonElement.selectFirst("a")?.attr("href") ?: return@forEach
                    val seasonDoc = app.get(seasonLink, headers = headers).document
                    val seasonNumText = seasonDoc.selectFirst("h2.entry-title, h1.entry-title")?.text()
                    val seasonNum = Regex("""الموسم (\d+)""").find(seasonNumText ?: "")?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1

                    seasonDoc.select("div.ep-item a, div.episode-item a").forEach { episodeLink ->
                        val epHref = episodeLink.attr("href")
                        val epTitle = episodeLink.select("span.ep-title, span.ep-name").text()
                            ?: episodeLink.attr("title")
                            ?: episodeLink.text()
                        val epNum = episodeLink.select("span.ep-num, span.ep-number").text().toIntOrNull()
                            ?: Regex("""الحلقة (\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else {
                // موسم واحد فقط
                document.select("div.ep-item a, div.episode-item a").forEach { episodeLink ->
                    val epHref = episodeLink.attr("href")
                    val epTitle = episodeLink.select("span.ep-title, span.ep-name").text()
                        ?: episodeLink.attr("title")
                        ?: episodeLink.text()
                    val epNum = episodeLink.select("span.ep-num, span.ep-number").text().toIntOrNull()
                        ?: Regex("""الحلقة (\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

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
            // فيلم
            val watchLinks = document.select("ul.quality-list li a, div.watch-list a, a.watch-btn").map {
                val embedUrl = it.attr("data-url") ?: it.attr("href")
                val name = it.text().ifEmpty { it.select("span.quality").text() }
                
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
        val embedPage = app.get(data, referer = "$mainUrl/", headers = headers).document
        val iframeSrc = embedPage.selectFirst("iframe")?.attr("src") ?: return false

        loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)

        return true
    }
}
