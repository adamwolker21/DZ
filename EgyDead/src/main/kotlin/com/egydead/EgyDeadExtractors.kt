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

// v11 Change: Update headers to be more comprehensive and mimic a real mobile browser
private val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "sec-ch-ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
    "sec-ch-ua-mobile" to "?1",
    "sec-ch-ua-platform" to """"Android"""",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// This function now uses the comprehensive headers by default
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        null
    }
}

// All extractors below will now automatically use the updated BROWSER_HEADERS
// via the safeGetAsDocument function without needing individual changes.

class Dingtezuni : ExtractorApi() {
    override var name = "EarnVids"
    override var mainUrl = "dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer) ?: return
        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data() ?: return
        val unpackedJs = getAndUnpack(packedJs)
        val hls2Link = Regex(""""hls2":\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)
        if (hls2Link != null) {
            callback(newExtractorLink(this.name, this.name, hls2Link) { this.referer = "" })
        }
    }
}

class Forafile : ExtractorApi() {
    override var name = "Forafile"; override var mainUrl = "forafile.com"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer)
        val packedJs = document?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                callback(newExtractorLink(this.name, this.name, mp4Link) { this.referer = url })
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

class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())
class Bigwarp : PackedJsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())
