package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
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
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "/movies" to "أحدث الأفلام",
        "/series" to "أحدث المسلسلات",
        "/genre/افلام-انمي" to "أفلام أنمي",
        "/genre/افلام-اسيوية" to "أفلام أسيوية",
        "/genre/افلام-تركية" to "أفلام تركية",
        "/genre/افلام-هندية" to "أفلام هندية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page", headers = headers).document
        val home = document.select("div.itemviews div.postDiv, div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val title = anchor.selectFirst("div.h1, h3 a")?.text() ?: "No Title"
        val posterElement = anchor.selectFirst("div.imgdiv-class img, div.post-thumb img")
        val posterUrl = posterElement?.attr("data-src") 
            ?: posterElement?.attr("src")
            ?: anchor.selectFirst("div.post-thumb a")?.attr("style")?.substringAfter("url(")?.substringBefore(")")
        
        val isSeries = href.contains("/series/") || this.selectFirst("span.quality:contains(حلقة)") != null
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
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
        val year = Regex("""\d{4}""").find(document.select("span:contains(موعد الصدور)").firstOrNull()?.text() ?: "")?.value?.toIntOrNull()
        val tags = document.select("span:contains(تصنيف) a").map { it.text() }
        
        if (url.contains("/series/") || document.select("div#seasonList, div#epAll").isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv")
            
            val episodeSelector = "div#epAll a, div.ep-item a"

            if (seasonElements.isNotEmpty()) {
                seasonElements.apmap { seasonElement ->
                    val seasonLink = seasonElement.attr("onclick")?.substringAfter("'")?.substringBefore("'")
                        ?: seasonElement.selectFirst("a")?.attr("href") ?: return@apmap
                    val absoluteSeasonLink = if (seasonLink.startsWith("http")) seasonLink else "$mainUrl$seasonLink"
                    val seasonNum = Regex("""\d+""").find(seasonElement.selectFirst("div.title")?.text() ?: "")?.value?.toIntOrNull()
                    val seasonDoc = app.get(absoluteSeasonLink, headers = headers).document
                    
                    seasonDoc.select(episodeSelector).forEach { ep ->
                        episodes.add(
                            newEpisode(ep.attr("href")) {
                                name = ep.text().trim()
                                season = seasonNum
                                episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                            }
                        )
                    }
                }
            } else {
                document.select(episodeSelector).forEach { ep ->
                    episodes.add(
                        newEpisode(ep.attr("href")) {
                            name = ep.text().trim()
                            season = 1
                            episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    private suspend fun M3u8Helper(
        m3u8url: String,
        referer: String,
        qualityName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Content = app.get(m3u8url, headers = headers.plus("Referer" to referer)).text
        val masterPlaylistRegex = Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+)x(\d+).*?\n(.*?)\s""")
        
        if (m3u8Content.contains("EXT-X-STREAM-INF")) {
            masterPlaylistRegex.findAll(m3u8Content).forEach { match ->
                val (width, height, link) = match.destructured
                val quality = getQualityFromName("${height}p")
                val absoluteLink = if (link.startsWith("http")) link else {
                    m3u8url.substringBeforeLast("/") + "/" + link
                }
                callback.invoke(
                    newExtractorLink(
                        source = "$name - $qualityName",
                        name = "$name - $qualityName ${quality.name}",
                        url = absoluteLink,
                        referer = referer,
                        quality = quality.value,
                        isM3u8 = true
                    )
                )
            }
        } else {
             callback.invoke(
                newExtractorLink(
                    source = "$name - $qualityName",
                    name = "$name - $qualityName",
                    url = m3u8url,
                    referer = referer,
                    quality = getQualityFromName(qualityName).value,
                    isM3u8 = true
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        document.select("ul.tabs-ul li").forEachIndexed { index, serverElement ->
            val serverUrl = serverElement.attr("onclick").substringAfter("href = '").substringBefore("'")
            if(serverUrl.isBlank()) return@forEachIndexed

            try {
                val playerPageContent = app.get(serverUrl, headers = headers).text
                val m3u8Link = Regex("""(https?://.*?\.m3u8)""").find(playerPageContent)?.groupValues?.get(1)

                if (m3u8Link != null) {
                    val serverName = "S${index + 1}" // Shorter name: S1, S2
                    M3u8Helper(m3u8Link, serverUrl, serverName, callback)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return true
    }
}
