package com.asiatv.one

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.Qualities

// Base class for custom extractors
abstract class AsiaTvExtractor : ExtractorApi() {
    override val name = "AsiaTvCustom"
    override var mainUrl = "" // Use var instead of val
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
    override var mainUrl = "asiatvplayer.com" // Use var

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedHeaders = mapOf("Referer" to (referer ?: "https://asiawiki.me/"))
        val document = app.get(url, headers = embedHeaders).document
        
        val script = document.selectFirst("script:containsData(eval)")?.data() ?: return
        val unpackedScript = getAndUnpack(script)
        
        // Extract direct MP4 links using the correct ExtractorLink constructor
        val mp4Regex = Regex("""file:"([^"]+\.mp4)"\s*,\s*label:"([^"]+)"""")
        mp4Regex.findAll(unpackedScript).forEach { match ->
            val fileUrl = match.groupValues[1]
            val qualityLabel = match.groupValues[2]
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} MP4",
                    url = fileUrl,
                    referer = url,
                    quality = when {
                        qualityLabel.contains("720") -> Qualities.P720.value
                        qualityLabel.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    },
                    isM3u8 = false,
                )
            )
        }

        // Extract the m3u8 link using the correct approach
        val m3u8Url = Regex("""file:"(.*?(?:\.m3u8))"""").find(unpackedScript)?.groupValues?.get(1)
        if (m3u8Url != null) {
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
