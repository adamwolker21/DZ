package com.krmzi

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class KrmziProvider : MainAPI() {
    override var mainUrl = "https://krmzi.org"
    override var name = "Krmzi"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/series-list/" to "قائمة المسلسلات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val home = document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var href = this.selectFirst("a")?.attr("href") ?: return null
        
        if (href.contains("url=")) {
            try {
                val encodedUrl = href.substringAfter("url=").substringBefore("&")
                val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                href = String(Base64.decode(decodedUrl, Base64.DEFAULT))
            } catch (e: Exception) { }
        }
        
        val title = this.selectFirst(".title")?.text()?.trim() ?: return null
        val style = this.selectFirst(".imgBg, .imgSer")?.attr("style") ?: ""
        val posterUrl = style.substringAfter("url(").substringBefore(")").replace("'", "").replace("\"", "")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val seriesInfo = document.selectFirst(".singleSeries") ?: return null
        val fullTitle = seriesInfo.selectFirst("h1 a")?.text()?.trim() 
            ?: seriesInfo.selectFirst("h1")?.text()?.trim() 
            ?: return null
            
        val title = fullTitle.replace("مسلسل", "").trim()

        val style = seriesInfo.selectFirst(".img")?.attr("style") ?: ""
        val poster = style.substringAfter("url(").substringBefore(")").replace("'", "").replace("\"", "")
        
        val plot = seriesInfo.selectFirst(".story")?.text()?.trim()

        val episodes = document.select("article.postEp").mapNotNull { ep ->
            var epHref = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            
            if (epHref.contains("url=")) {
                try {
                    val encodedUrl = epHref.substringAfter("url=").substringBefore("&")
                    val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                    epHref = String(Base64.decode(decodedUrl, Base64.DEFAULT))
                } catch (e: Exception) {}
            }
            
            val epTitle = ep.selectFirst(".title")?.text()?.trim() ?: "حلقة"
            val epNumStr = ep.select(".episodeNum span").last()?.text()?.trim()
            val epNum = epNumStr?.toIntOrNull()

            val epPosterStyle = ep.selectFirst(".imgSer")?.attr("style") ?: ""
            val epPoster = epPosterStyle.substringAfter("url(").substringBefore(")").replace("'", "").replace("\"", "")

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = epPoster
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val embedLink = document.selectFirst("a.fullscreen-clickable")?.attr("href") ?: return false
        
        val postParamMatch = Regex("post=([^&]+)").find(URLDecoder.decode(embedLink, "UTF-8"))
            ?: Regex("post=([^&]+)").find(embedLink)
            
        val postParam = postParamMatch?.groupValues?.get(1)

        if (postParam != null) {
            try {
                val jsonString = String(Base64.decode(postParam, Base64.DEFAULT))
                val jsonData = AppUtils.parseJson<ServersData>(jsonString)
                
                jsonData.servers?.forEach { server ->
                    val serverName = server.name ?: return@forEach
                    val serverId = server.id ?: return@forEach
                    
                    when (serverName.lowercase()) {
                        "ok" -> {
                            val okUrl = "https://ok.ru/videoembed/$serverId"
                            loadExtractor(okUrl, data, subtitleCallback, callback)
                        }
                        "estream", "turk" -> {
                            // التوجيه إلى نطاق سيرفر Turk الصحيح
                            val eUrl = "https://arabveturk.com/embed-$serverId.html"
                            loadExtractor(eUrl, data, subtitleCallback, callback)
                        }
                        "arab hd" -> {
                            val arabUrl = "https://arabhd.onl/embed-$serverId.html"
                            loadExtractor(arabUrl, data, subtitleCallback, callback)
                        }
                        "red hd" -> {
                            // التوجيه إلى نطاق سيرفر Red HD الصحيح
                            val redUrl = "https://iplayerhls.com/e/$serverId"
                            loadExtractor(redUrl, data, subtitleCallback, callback)
                        }
                        else -> {
                            if (serverId.startsWith("http")) {
                                loadExtractor(serverId, data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
    
    data class ServerJson(
        @JsonProperty("name") val name: String?,
        @JsonProperty("id") val id: String?
    )
    
    data class ServersData(
        @JsonProperty("servers") val servers: List<ServerJson>?
    )
}
