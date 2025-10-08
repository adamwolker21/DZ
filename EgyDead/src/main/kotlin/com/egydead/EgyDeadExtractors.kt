package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import org.jsoup.nodes.Document

// A list to hold all our extractors
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(),
)

// --- Full headers from your cURL data to perfectly mimic a browser ---
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
    "Sec-Ch-Ua-Mobile" to "?0",
    "Sec-Ch-Ua-Platform" to "\"Linux\"",
    "Sec-Fetch-Dest" to "iframe",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "cross-site",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
)

// --- StreamHG Handlers ---
private abstract class StreamHGBase : ExtractorApi() {
    override var name = "StreamHG"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer, headers = BROWSER_HEADERS).document
        
        Log.d(name, "Page requested for $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                loadExtractor(httpsify(m3u8Link), url, subtitleCallback, callback)
            } else {
                 Log.e(name, "m3u8 link not found in unpacked JS for $url")
            }
        } else {
            Log.e(name, "Packed JS not found on page for $url. Check for Cloudflare.")
        }
    }
}
private class StreamHG : StreamHGBase() { override var mainUrl = "hglink.to" }
private class Davioad : StreamHGBase() { override var mainUrl = "davioad.com" }
private class Haxloppd : StreamHGBase() { override var mainUrl = "haxloppd.com" }
private class Kravaxxa : StreamHGBase() { override var mainUrl = "kravaxxa.com" }
private class Cavanhabg : StreamHGBase() { override var mainUrl = "cavanhabg.com"}

// --- Forafile Handler ---
private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url, referer = referer, headers = BROWSER_HEADERS).document

        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)"""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                loadExtractor(mp4Link, url, subtitleCallback, callback)
            } else {
                 Log.e(name, "mp4 link not found in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found on page for $url. Check for Cloudflare.")
        }
    }
}

// --- DoodStream Handlers ---
private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = app.get(newUrl, referer = referer, headers = BROWSER_HEADERS).text
        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) {
            Log.e(name, "Could not find doodToken for $url. Check for Cloudflare.")
            return
        }
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
        loadExtractor(trueUrl, newUrl, subtitleCallback, callback)
    }
}
private class DoodStream : DoodStreamBase() { override var mainUrl = "doodstream.com" }
private class DsvPlay : DoodStreamBase() { override var mainUrl = "dsvplay.com" }

// --- Mixdrop Handlers ---
private abstract class MixdropBase : ExtractorApi() {
    override var name = "Mixdrop"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer, headers = BROWSER_HEADERS).document
        val script = res.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = Regex("""MDCore\.wurl="([^"]+)""").find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null) {
                val finalUrl = "https:${videoUrl}"
                loadExtractor(finalUrl, url, subtitleCallback, callback)
            } else {
                Log.e(name, "MDCore.wurl not found in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found on page for $url. Check for Cloudflare.")
        }
    }
}
private class Mixdrop : MixdropBase() { override var mainUrl = "mixdrop.ag" }
private class Mdfx9dc8n : MixdropBase() { override var mainUrl = "mdfx9dc8n.net" }
