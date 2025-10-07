package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor

// A list to hold all our extractors
val extractorList = listOf(
    StreamHG(),
    Forafile(),
    DoodStream(),
    Mixdrop(),
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

// Extracts from dood.stream domains
private class DoodStream : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "dood.stream"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val response = app.get(newUrl, referer = referer).text
        val doodToken = response.substringAfter("'/pass_md5/").substringBefore("',")
        val md5PassUrl = "https://${mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z" // "z" is a random string
        // Suppress the deprecation warning to allow the build to complete.
        @Suppress("DEPRECATION")
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = trueUrl,
                referer = newUrl,
                quality = 1080,
                isM3u8 = trueUrl.contains(".m3u8")
            )
        )
    }
}

// Extracts from mixdrop.co
private class Mixdrop : ExtractorApi() {
    override var name = "Mixdrop"
    override var mainUrl = "mixdrop.co"
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
                // Suppress the deprecation warning to allow the build to complete.
                @Suppress("DEPRECATION")
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = "https:${videoUrl}",
                        referer = url,
                        quality = 720,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}
