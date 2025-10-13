package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document

val extractorList = listOf(
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
    Dingtezuni(), 
)

private val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        null
    }
}

class Dingtezuni : ExtractorApi() {
    override var name = "EarnVids"
    override var mainUrl = "dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Handle both /v/ (streaming) and /file/ (download) links by normalizing them
        val pageUrl = url.replace("/file/", "/v/")
        val document = safeGetAsDocument(pageUrl, referer) ?: return
        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data() ?: return
        
        val unpackedJs = getAndUnpack(packedJs)
        
        // v17 Final Fix: The regex was wrong, it should look for double quotes, not single quotes.
        val hls2Link = Regex(""""hls2":\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)

        if (hls2Link != null) {
            callback(newExtractorLink(this.name, this.name, hls2Link) { this.referer = "" })
        }
    }
}

class Forafile : ExtractorApi() {
    override var name = "Forafile"; override var mainUrl = "forafile.com"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            else -> "General Packed"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null

        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
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
        callback(newExtractorLink(this.name, this.name, trueUrl) { this.referer = newUrl })
    }
}
class DoodStream : DoodStreamBase() { override var name = "DoodStream"; override var mainUrl = "doodstream.com" }
class DsvPlay : DoodStreamBase() { override var name = "DsvPlay"; override var mainUrl = "dsvplay.com" }

abstract class PackedJsExtractorBase(override var name: String, override var mainUrl: String, private val regex: Regex) : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer)
        val script = doc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = regex.find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null && videoUrl.isNotBlank()) {
                val finalUrl = if (videoUrl.startsWith("//")) "https:${videoUrl}" else videoUrl
                callback(newExtractorLink(this.name, this.name, finalUrl) { this.referer = url })
            }
        }
    }
}

class Mixdrop : PackedJsExtractorBase("dingtezuni", "dingtezuni.com", """MDCore\.wurl="([^"]+)""".toRegex())
class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "dingtezuni.com", """MDCore\.wurl="([^"]+)""".toRegex())
class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())
class Bigwarp : PackedJsExtractorBase("dingtezuni", "dingtezuni.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "dingtezuni.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
