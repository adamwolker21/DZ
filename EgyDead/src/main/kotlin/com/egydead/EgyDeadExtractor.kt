package com.egydead.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.newExtractorLink

/**
 * Extracts video links from StreamHG servers.
 * It works by finding and de-obfuscating a packed JavaScript code block.
 */
open class StreamHGExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hglink.to" // Base domain to match against iframe URLs
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fetch the page containing the video player
        val doc = app.get(url, referer = referer).document

        // Find the script that contains the packed (obfuscated) JavaScript
        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return // Exit if no packed script is found

        // De-obfuscate the JavaScript to reveal the video source
        val unpacked = getAndUnpack(packedJs)

        // Use regex to find the m3u8 link within the unpacked script
        val m3u8Link = Regex("""sources:\s*\[\s*\{\s*file:\s*"(.*?)"""").find(unpacked)?.groupValues?.get(1)

        if (m3u8Link != null) {
            // If a link is found, invoke the callback with the extractor link details
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = httpsify(m3u8Link),
                    referer = referer ?: mainUrl, // Use mainUrl as a fallback referer
                    quality = Qualities.Unknown.value,
                    isM3u8 = true, // This link is an HLS playlist
                )
            )
        }
    }
}

/**
 * Extracts video links from Forafile servers.
 * It works by finding the direct video URL in a <source> tag.
 */
open class ForafileExtractor : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "https://forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        // Find the video source URL from the <source> tag's 'src' attribute
        val videoUrl = document.selectFirst("source[src]")?.attr("src")

        if (!videoUrl.isNullOrBlank()) {
            // If a URL is found, invoke the callback
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = referer ?: mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8"), // Check if it's an m3u8 link
                )
            )
        }
    }
}
