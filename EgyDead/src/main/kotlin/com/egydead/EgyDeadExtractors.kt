package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor

// A list to hold all our extractors, including the new domain handlers
val extractorList = listOf(
    // Updated StreamHG Handlers
    StreamHG(),
    Davioad(),
    Haxloppd(),
    Kravaxxa(),

    // Updated Forafile Handler
    Forafile(),

    // Existing Handlers
    DoodStream(),
    DsvPlay(),
    Mixdrop(),
    Mdfx9dc8n(),
    // TODO: Implement the following extractors
    // BigWarp(),
    // EarnVids(),
    // VidGuard(),
)

// --- StreamHG Handlers ---
// Base abstract class with the updated extraction logic
private abstract class StreamHGBase : ExtractorApi() {
    override var name = "StreamHG"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        // Find the script with the packed JS code
        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            // Unpack it to reveal the real source link
            val unpacked = getAndUnpack(packedJs)
            // Regex to find the master.m3u8 link inside the unpacked script
            val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                loadExtractor(httpsify(m3u8Link), referer, subtitleCallback, callback)
            }
        }
    }
}
// Child classes for each domain
private class StreamHG : StreamHGBase() {
    override var mainUrl = "hglink.to"
}
private class Davioad : StreamHGBase() {
    override var mainUrl = "davioad.com"
}
private class Haxloppd : StreamHGBase() {
    override var mainUrl = "haxloppd.com"
}
private class Kravaxxa : StreamHGBase() {
    override var mainUrl = "kravaxxa.com"
}


// --- Forafile Handler ---
// Updated extractor with new logic
private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url, referer = referer).document

        // First, try the direct and easiest method: find the video tag src
        val videoUrl = document.selectFirst("video.jw-video")?.attr("src")
        if (videoUrl?.isNotBlank() == true) {
            loadExtractor(videoUrl, referer, subtitleCallback, callback)
            return
        }

        // If the direct method fails, fall back to unpacking the JS
        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            // Regex to find the video.mp4 link
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)"""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                loadExtractor(mp4Link, referer, subtitleCallback, callback)
            }
        }
    }
}

// --- DoodStream Handlers ---
private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val response = app.get(newUrl, referer = referer).text
        val doodToken = response.substringAfter("'/pass_md5/").substringBefore("',")
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
        loadExtractor(trueUrl, newUrl, subtitleCallback, callback)
    }
}
private class DoodStream : DoodStreamBase() {
    override var mainUrl = "doodstream.com"
}
private class DsvPlay : DoodStreamBase() {
    override var mainUrl = "dsvplay.com"
}

// --- Mixdrop Handlers ---
private abstract class MixdropBase : ExtractorApi() {
    override var name = "Mixdrop"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = Regex("""MDCore\.wurl="([^"]+)""").find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null) {
                val finalUrl = "https:${videoUrl}"
                loadExtractor(finalUrl, url, subtitleCallback, callback)
            }
        }
    }
}
private class Mixdrop : MixdropBase() {
    override var mainUrl = "mixdrop.ag"
}
private class Mdfx9dc8n : MixdropBase() {
    override var mainUrl = "mdfx9dc8n.net"
}
