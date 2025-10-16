package com.asiatv.one

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities.Companion.qualityFromName

// Base class for custom extractors
open class AsiaTvExtractor : ExtractorApi() {
    override val name = "AsiaTvCustom"
    override var mainUrl = "" 
    override val requiresReferer = true

    // getUrl will be implemented by specific extractor classes
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {}
}

// Extractor for AsiaTvPlayer
class AsiaTvPlayer : AsiaTvExtractor() {
    override val name = "AsiaTvPlayer"
    override val mainUrl = "asiatvplayer.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // The referer for the embed page is the asiawiki.me page
        val embedHeaders = mapOf("Referer" to (referer ?: "https://asiawiki.me/"))
        val document = app.get(url, headers = embedHeaders).document
        
        // Find the script containing the packed player data
        val script = document.selectFirst("script:containsData(eval)")?.data() ?: return
        val unpackedScript = getAndUnpack(script)
        
        // Extract direct MP4 links first as they are more reliable
        val mp4Regex = Regex("""file:"([^"]+\.mp4)"\s*,\s*label:"([^"]+)"""")
        mp4Regex.findAll(unpackedScript).forEach { match ->
            val fileUrl = match.groupValues[1]
            val qualityLabel = match.groupValues[2]
            callback(
                ExtractorLink(
                    this.name,
                    "${this.name} - MP4",
                    fileUrl,
                    url, // Referer for the video file
                    qualityFromName(qualityLabel),
                    isM3u8 = false,
                )
            )
        }

        // Extract the m3u8 link as a fallback/alternative
        val m3u8Url = Regex("""file:"(.*?(?:\.m3u8))"""").find(unpackedScript)?.groupValues?.get(1)
        if (m3u8Url != null) {
            // The referer for the m3u8 manifest should be the player's domain
            val m3u8Headers = mapOf(
                "Origin" to "https://www.asiatvplayer.com",
                "Referer" to "https://www.asiatvplayer.com/"
            )
            M3u8Helper.generateM3u8(
                this.name,
                m3u8Url,
                "https://www.asiatvplayer.com/",
                headers = m3u8Headers
            ).forEach(callback)
        }
    }
                            }
