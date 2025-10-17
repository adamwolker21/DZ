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

            // v11: Re-adding full script logging and adding diagnostic checks
            Log.d(TAG, "Unpacked Script (length ${unpackedScript.length}): $unpackedScript")
            Log.d(TAG, "Diagnostic check for 'master.m3u8': ${unpackedScript.contains("master.m3u8")}")
            Log.d(TAG, "Diagnostic check for 'v.mp4': ${unpackedScript.contains("v.mp4")}")


            // v11: Using a more robust and flexible Regex
            val sourceRegex = Regex("""file\s*:\s*["']([^"']+)["'](?:,\s*label\s*:\s*["']([^"']+)["'])?""")
            sourceRegex.findAll(unpackedScript).forEach { match ->
                val videoUrl = match.groupValues[1]
                val qualityLabel = match.groupValues[2].ifEmpty { null }

                if (videoUrl.contains("master.m3u8")) {
                    Log.d(TAG, "Found M3U8 URL: $videoUrl")
                    sources.add(
                        newExtractorLink(
                            source = this.name,
                            name = "HLS (Auto)",
                            url = videoUrl,
                        ) {
                            this.referer = refererUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else if (videoUrl.contains("v.mp4")) {
                    Log.d(TAG, "Found MP4 URL: $videoUrl with label: $qualityLabel")
                    sources.add(
                        newExtractorLink(
                            source = this.name,
                            name = qualityLabel ?: "SD",
                            url = videoUrl,
                        ) {
                            this.referer = refererUrl
                            this.quality = getQualityFromName(qualityLabel)
                        }
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "An exception occurred: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d(TAG, "Returning ${sources.size} sources.")
        return sources
    }
}
