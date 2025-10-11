package com.egydead

// Imports from Provider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

// Imports from Extractors
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONObject
import org.jsoup.nodes.Document


class EgyDeadProvider : MainAPI() {
    // This is the known working base of the provider.
    override var mainUrl = "https://tv6.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url, interceptor = cloudflareKiller)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = headers, data = data, cookies = cookies, interceptor = cloudflareKiller).document
            }
            return document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val document = app.get(url, interceptor = cloudflareKiller).document
        val home = document.select("li.movieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val cleanedTitle = title.replace("مشاهدة", "").trim().replace(Regex("^(فيلم|مسلسل)"), "").trim()
        val isSeries = title.contains("مسلسل") || title.contains("الموسم")
        return if (isSeries) newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(cleanedTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = cloudflareKiller).document
        return document.select("li.movieItem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflareKiller).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val isSeries = document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodes = document.select("div.EpsList li a").mapNotNull { ep ->
                val epNum = ep.attr("title").substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
                newEpisode(ep.attr("href")) {
                    this.name = ep.text().trim()
                    this.episode = epNum
                }
            }
            val seriesTitle = pageTitle.replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "").trim()
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl; this.plot = plot
            }
        } else {
            val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()
            return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        val servers = watchPageDoc.select("div.mob-servers li")

        if (servers.isEmpty()) return false

        val foundAUrl = AtomicBoolean(false)

        servers.apmap { serverLi: Element ->
            try {
                val link = serverLi.attr("data-link")
                if (link.isNotBlank()) {
                    loadExtractor(link, data, subtitleCallback) { foundLink ->
                        callback(foundLink)
                        foundAUrl.set(true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return foundAUrl.get()
    }
}

// =================================================================================
// START OF EXTRACTORS
// =================================================================================

// The complete list of extractors, all fixed to work with the modern API.
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(), Dumbalag(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
)

// Using a mobile User-Agent.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar=q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

private val extractorCloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = extractorCloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("Extractor", "safeGetAsDocument FAILED for $url: ${e.message}")
        null
    }
}

// The classes must be public to be accessible in the public `extractorList`.
abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // The full list of potential hosts.
    private val potentialHosts = listOf(
        "kravaxxa.com",
        "cavanhabg.com",
        "dumbalag.com",
        "davioad.com",
        "haxloppd.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

            val packedJs = doc.select("script")
                .map { it.data() }
                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                .maxByOrNull { it.length }

            if (packedJs.isNullOrBlank()) continue

            try {
                val unpacked = getAndUnpack(packedJs)
                
                val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";").trim()
                val jsonObject = JSONObject(jsonObjectString)
                val m3u8Link = jsonObject.getString("hls2")

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return 
                }
            } catch (e: Exception) {
                Log.e("StreamHG", "Failed on host '$host': ${e.message}")
            }
        }
    }
}

// Defining the classes for each domain.
class StreamHG : StreamHGBase("StreamHG", "hglink.to")
class Davioad : StreamHGBase("StreamHG (Davioad)", "davioad.com")
class Haxloppd : StreamHGBase("StreamHG (Haxloppd)", "haxloppd.com")
class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
class Cavanhabg : StreamHGBase("StreamHG (Cavanhabg)", "cavanhabg.com")
class Dumbalag : StreamHGBase("StreamHG (Dumbalag)", "dumbalag.com" )

class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer)
        val packedJs = document?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                callback(
                    newExtractorLink(this.name, this.name, mp4Link) {
                        this.referer = url
                    }
                )
            }
        }
    }
}

abstract class DoodStreamBase : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = try { app.get(newUrl, referer = referer, headers = BROWSER_HEADERS).text } catch (e: Exception) { null } ?: return

        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) return
        
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
        callback(
            newExtractorLink(this.name, this.name, trueUrl, type = ExtractorLinkType.M3U8) {
                this.referer = newUrl
            }
        )
    }
}
class DoodStream : DoodStreamBase() { override var name = "DoodStream"; override var mainUrl = "doodstream.com" }
class DsvPlay : DoodStreamBase() { override var name = "DsvPlay"; override var mainUrl = "dsvplay.com" }

abstract class PackedJsExtractorBase(
    override var name: String,
    override var mainUrl: String,
    private val regex: Regex
) : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer)
        val script = doc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = regex.find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null && videoUrl.isNotBlank()) {
                val finalUrl = if (videoUrl.startsWith("//")) "https:${videoUrl}" else videoUrl
                callback(
                    newExtractorLink(this.name, this.name, finalUrl) {
                        this.referer = url
                    }
                )
            }
        }
    }
}

class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())

class Bigwarp : PackedJsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())
