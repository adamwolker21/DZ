package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv5.egydead.live"
    override var name = "EgyDead"
    override val instantLinkLoading = true
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-1/" to "مسلسلات أجنبية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d8%a7/" to "مسلسلات تركية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية"
    )

    companion object {
        private fun String?.toIntOrNull(): Int? {
            return this?.toIntOrNull()
        }

        private fun String?.toRatingInt(): Int? {
            return this?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull()?.times(1000)?.toInt()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data + "page/$page/"
        val document = app.get(mainUrl + url, headers = headers).document
        val home = document.select("article.item, .movie, .post, .item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, .title, .name")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src"))
        val quality = this.selectFirst(".quality")?.text()?.trim()

        val type = when {
            href.contains("/movie/") || href.contains("/film/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        }
    }

    private fun getQualityFromString(quality: String?): Quality {
        return when {
            quality?.contains("1080") == true -> Quality.FullHDP
            quality?.contains("720") == true -> Quality.HD
            quality?.contains("480") == true -> Quality.SD
            else -> Quality.Unknown
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeToUrl()}"
        val document = app.get(url, headers = headers).document
        return document.select("article.item, .movie, .post, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: "No Title"
        val description = document.selectFirst(".description, .content, .story")?.text()?.trim()
        val poster = fixUrl(document.selectFirst(".poster img, .thumbnail img")?.attr("src"))
        val background = fixUrl(document.selectFirst(".backdrop")?.attr("src"))
        val year = document.selectFirst(".year, .date")?.text()?.toIntOrNull()
        val rating = document.selectFirst(".rating, .score")?.text()?.toRatingInt()
        val tags = document.select(".genre a, .tags a").map { it.text() }
        val status = when (document.selectFirst(".status")?.text()?.lowercase()) {
            "مكتمل", "منتهي" -> ShowStatus.Completed
            "مستمر", "يعرض" -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = document.select(".episodes li, .episode-list li").map { li ->
            val episodeTitle = li.selectFirst(".title, .name")?.text()?.trim() ?: "الحلقة"
            val episodeHref = fixUrl(li.selectFirst("a")?.attr("href") ?: return@map null)
            val episodeNumber = li.selectFirst(".number, .ep")?.text()?.toIntOrNull()
            val episodePoster = fixUrl(li.selectFirst("img")?.attr("src"))

            newEpisode(episodeHref) {
                this.name = episodeTitle
                this.episode = episodeNumber
                this.posterUrl = episodePoster
            }
        }.filterNotNull()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                this.showStatus = status
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.rating = rating
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
        val document = app.get(data, headers = headers).document
        
        // محاولة استخراج iframes أولاً
        val iframes = document.select("iframe")
        iframes.forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        // محاولة استخراج من مصادر أخرى
        val videoSources = document.select("source[src], video source[src]")
        videoSources.forEach { source ->
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        // محاولة استخراج من الروابط المباشرة
        val links = document.select("a[href*='.m3u8'], a[href*='.mp4']")
        links.forEach { link ->
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            loadExtractor(href, data, subtitleCallback, callback)
        }
        
        return iframes.isNotEmpty() || videoSources.isNotEmpty() || links.isNotEmpty()
    }

    private fun String.encodeToUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application
