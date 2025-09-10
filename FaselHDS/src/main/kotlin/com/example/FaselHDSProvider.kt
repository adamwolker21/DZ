package com.example

// All the necessary imports from a working provider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// This is a working provider structure
class FaselHDSProvider : MainAPI() {
    // 1. Update basic info for FaselHDS
    override var mainUrl = "https://www.faselhd.club"
    override var name = "FaselHDS (New)" // New name to avoid cache issues
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // A working WebView implementation that is compatible with older projects
    private suspend fun getAndParse(url: String): org.jsoup.nodes.Document {
        val html = app.get(
            url,
            interceptor = WebViewResolver(
                // A simple predicate that waits for a common arabic word on the site
                predicate = { it.contains("الرئيسية") }
            )
        ).text
        return org.jsoup.Jsoup.parse(html)
    }

    // 2. Update main page sections for FaselHDS
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
        val document = getAndParse("$mainUrl${request.data}/page/$page")
        // 3. Update the selector for homepage items
        val home = document.select("div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }
    
    // 4. Update selectors for search results
    private fun Element.toSearchResult(): SearchResponse {
        val href = this.selectFirst("a")!!.attr("href")
        val title = this.selectFirst("h3 a")!!.text()
        val posterUrl = this.selectFirst("div.post-thumb a")
            ?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getAndParse("$mainUrl/?s=$query")
        return document.select("div.post-listing article.item-list").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getAndParse(url)
        
        // 5. Update selectors for loading movie/series details
        val title = document.selectFirst("div.title-container h1.entry-title")!!.text().trim()
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")!!.text().trim()
        val year = document.select("div.meta-bar span.year").firstOrNull()?.text()?.toIntOrNull()

        val isTvSeries = document.select("div#season-list").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // This logic requires a simple get, as WebView is only needed for the main pages
            document.select("div.season-list-item a").forEach { seasonLink ->
                val seasonUrl = seasonLink.attr("href")
                // Use a simple app.get for season pages as they are likely not protected
                val seasonDoc = app.get(seasonUrl).document
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
        // This logic remains the same and should work
        val embedPage = app.get(data, referer = "$mainUrl/").document
        val iframeSrc = embedPage.selectFirst("iframe")?.attr("src") ?: return false
        loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
