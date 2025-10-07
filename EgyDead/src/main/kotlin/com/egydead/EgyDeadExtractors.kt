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
    StreamHG(),
    Forafile(),
    DoodStream(),   // Official Domain
    DsvPlay(),      // Alternative Domain
    Mixdrop(),      // Official Domain
    Mdfx9dc8n(),    // Alternative Domain
    // TODO: Implement the following extractors
    // BigWarp(),
    // EarnVids(),
    // VidGuard(),
)

// Extracts from streamhg.org / hglink.to
private class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""sources:\[\{file:"(.*?)"\}\]""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                loadExtractor(httpsify(m3u8Link), referer, subtitleCallback, callback)
            }
        }
    }
}

// Extracts from forafile.com
private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url, referer = referer).document
        val videoUrl = document.selectFirst("source")?.attr("src")
        if (videoUrl != null) {
             loadExtractor(videoUrl, referer, subtitleCallback, callback)
        }
    }
}

// --- DoodStream Handlers ---
// Base class with the extraction logic
private open class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val response = app.get(newUrl, referer = referer).text
        val doodToken = response.substringAfter("'/pass_md5/").substringBefore("',")
        // Use this.mainUrl to build the pass_md5 URL correctly for each domain
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z" // "z" is a random string
        loadExtractor(trueUrl, newUrl, subtitleCallback, callback)
    }
}
// Child class for the official domain
private class DoodStream : DoodStreamBase() {
    override var mainUrl = "doodstream.com" // Updated to official domain
}
// Child class for the alternative domain
private class DsvPlay : DoodStreamBase() {
    override var mainUrl = "dsvplay.com"
}


// --- Mixdrop Handlers ---
// Base class with the extraction logic
private open class MixdropBase : ExtractorApi() {
    override var name = "Mixdrop"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
// Child class for the official domain
private class Mixdrop : MixdropBase() {
    override var mainUrl = "mixdrop.ag" // Updated to official domain
}
// Child class for the alternative domain
private class Mdfx9dc8n : MixdropBase() {
    override var mainUrl = "mdfx9dc8n.net"
}
