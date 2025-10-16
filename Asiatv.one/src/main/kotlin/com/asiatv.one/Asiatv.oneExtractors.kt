package com.asiatv.one

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper

// Base class for custom extractors
open class AsiaTvExtractor : ExtractorApi() {
    override val name = "AsiaTvCustom"
    override val mainUrl = "" // This will be overridden by each specific extractor
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Default implementation, to be overridden if needed
    }
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
        val headers = mapOf("Referer" to (referer ?: mainUrl))
        val document = app.get(url, headers = headers).document
        
        // Find the script containing the packed player data
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val packedData = script.data()
                val unpackedScript = getAndUnpack(packedData)
                
                // Extract the m3u8 link from the unpacked script
                val m3u8Url = Regex("file:\"(.*?m3u8.*?)\"").find(unpackedScript)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        m3u8Url,
                        url, // Use the embed URL as referer for the m3u8 request
                        headers = headers
                    ).forEach(callback)
                }
            }
        }
    }
}
