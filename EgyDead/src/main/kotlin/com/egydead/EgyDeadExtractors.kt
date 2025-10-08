package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log // Import Log for debugging

// A list to hold all our extractors
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(),
)

// --- Base Headers for mimicking a real browser ---
// We will fill this with the data from browser developer tools
val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
    "Accept-Language" to "en-US,en;q=0.9",
    // We will add more headers here once we get them
)

// --- StreamHG Handlers ---
private abstract class StreamHGBase : ExtractorApi() {
    override var name = "StreamHG"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Perform the request with browser-like headers
        val doc = app.get(url, referer = referer, headers = BROWSER_HEADERS).document
        
        // V16 DEBUGGING: Log the received HTML content
        Log.d(name, "Page content for $url:\n${doc.html()}")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                loadExtractor(httpsify(m3u8Link), url, subtitleCallback, callback)
            } else {
                 Log.d(name, "Packed JS found but m3u8 link not found after unpacking.")
            }
        } else {
            Log.d(name, "Packed JS not found on page.")
        }
    }
}
private class StreamHG : StreamHGBase() { override var mainUrl = "hglink.to" }
private class Davioad : StreamHGBase() { override var mainUrl = "davioad.com" }
private class Haxloppd : StreamHGBase() { override var mainUrl = "haxloppd.com" }
private class Kravaxxa : StreamHGBase() { override var mainUrl = "kravaxxa.com" }

// --- Forafile Handler ---
private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url, referer = referer, headers = BROWSER_HEADERS).document

        // V16 DEBUGGING: Log the received HTML content
        Log.d(name, "Page content for $url:\n${document.html()}")

        val videoUrlDirect = document.selectFirst("video.jw-video")?.attr("src")
        if (videoUrlDirect?.isNotBlank() == true) {
            loadExtractor(videoUrlDirect, url, subtitleCallback, callback)
            return
        }

        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)"""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                loadExtractor(mp4Link, url, subtitleCallback, callback)
            } else {
                Log.d(name, "Packed JS found but mp4 link not found after unpacking.")
            }
        } else {
             Log.d(name, "Packed JS not found on page.")
        }
    }
}

// --- DoodStream Handlers ---
private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val response = app.get(newUrl, referer = referer, headers = BROWSER_HEADERS).text
        // The rest of the logic should be fine, but we added headers to the initial request
        val doodToken = response.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) {
            Log.d(name, "Could not find doodToken. Response was:\n$response")
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
        
        // V16 DEBUGGING: Log the received HTML content
        Log.d(name, "Page content for $url:\n${res.html()}")

        val script = res.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = Regex("""MDCore\.wurl="([^"]+)""").find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null) {
                val finalUrl = "https:${videoUrl}"
                loadExtractor(finalUrl, url, subtitleCallback, callback)
            } else {
                Log.d(name, "Packed JS found but MDCore.wurl not found after unpacking.")
            }
        } else {
             Log.d(name, "Packed JS not found on page.")
        }
    }
}
private class Mixdrop : MixdropBase() { override var mainUrl = "mixdrop.ag" }
private class Mdfx9dc8n : MixdropBase() { override var mainUrl = "mdfx9dc8n.net" }
