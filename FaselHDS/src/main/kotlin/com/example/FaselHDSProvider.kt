package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// هذه هي النسخة الأساسية التي لا تستخدم أي أدوات قد تكون مفقودة في مشروعك
class FaselHDSProvider : MainAPI() {
    override var mainUrl = "https://www.faselhd.club"
    override var name = "FaselHDS"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    
    // سنحتفظ بـ User-Agent لأنه لا يزال أفضل فرصة لدينا
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/5.0 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
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
        // سنعود إلى الطريقة الأساسية والآمنة التي تضمن نجاح البناء
        val document = app.get(url, headers = headers).document
        val home = document.select("div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3 a")?.text() ?: "No Title"
        val posterUrl = this.selectFirst("div.post-thumb a")
            ?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document

        return document.select("div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("div.title-container h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
        val yearText = document.select("div.meta-bar span.year").firstOrNull()?.text()
        val year = yearText?.toIntOrNull()
        
        val isTvSeries = document.select("div#season-list").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.season-list-item a").forEach { seasonLink ->
                val seasonUrl = seasonLink.attr("href")
                val seasonDoc = app.get(seasonUrl, headers = headers).document
                val seasonNumText = seasonDoc.selectFirst("h2.entry-title")?.text()
                val seasonNum = Regex("""الموسم (\d+)""").find(seasonNumText ?: "")?.groupValues?.get(1)?.toIntOrNull()

                seasonDoc.select("div.ep-item a").forEach { episodeLink ->
                    val epHref = episodeLink.attr("href")
                    val epTitle = episodeLink.select("span.ep-title").text()
                    val epNum = episodeLink.select("span.ep-num").text().toIntOrNull()

                    episodes.add(
                        newEpisode(epHref) {
                            name = epTitle
                            season = seasonNum
                            episode = epNum
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            val watchLinks = document.select("ul.quality-list li a").map {
                val embedUrl = it.attr("data-url")
                val name = it.text()
                
                newEpisode(embedUrl) {
                    this.name = name
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, watchLinks) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
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
