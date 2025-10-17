package com.asiatv.one

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class AsiaTvPlayer : ExtractorApi() {
    override var name = "AsiaTvPlayer"
    override var mainUrl = "https://www.asiatvplayer.com"
    private val refererUrl = "$mainUrl/"
    override val requiresReferer = true
    private val TAG = "AsiaTvPlayer" // Tag for logging

    override suspend fun getUrl(
        url: String,
        referer: String?,
    ): List<ExtractorLink>? {
        Log.d(TAG, "Extractor invoked for URL: $url")
        val sources = mutableListOf<ExtractorLink>()

        try {
            val document = app.get(url, referer = referer).document
            Log.d(TAG, "Successfully fetched embed page content.")

            val script = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (script == null) {
                Log.e(TAG, "Could not find the packed script tag.")
                return null
            }
            Log.d(TAG, "Found packed script.")

            val unpackedScript = JsUnpacker(script).unpack()
            if (unpackedScript == null) {
                Log.e(TAG, "Failed to unpack the script.")
                return null
            }
            Log.d(TAG, "Script unpacked successfully.")
            
            // v9 Change: Print the entire unpacked script to the log for analysis.
            Log.d(TAG, "Unpacked Script Content: $unpackedScript")

            // Regex from v8 (will likely be adjusted after analyzing the new log)
            val m3u8Regex = Regex("""file\s*:\s*['"]([^'"]+master\.m3u8)['"]""")
            m3u8Regex.find(unpackedScript)?.let {
                val m3u8Url = it.groupValues[1]
                Log.d(TAG, "Found M3U8 URL: $m3u8Url")
                sources.add(
                    newExtractorLink(
                        source = this.name,
                        name = "HLS (Auto)",
                        url = m3u8Url,
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            val mp4Regex = Regex("""file\s*:\s*['"]([^'"]+v\.mp4)['"],\s*label\s*:\s*['"]([^'"]+)['"]""")
            mp4Regex.findAll(unpackedScript).forEach { match ->
                val videoUrl = match.groupValues[1]
                val qualityLabel = match.groupValues[2]
                Log.d(TAG, "Found MP4 URL: $videoUrl with label: $qualityLabel")
                sources.add(
                    newExtractorLink(
                        source = this.name,
                        name = qualityLabel,
                        url = videoUrl,
                    ) {
                        this.referer = refererUrl
                        this.quality = getQualityFromName(qualityLabel)
                    }
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "An exception occurred: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d(TAG, "Returning ${sources.size} sources.")
        return sources
    }
}
