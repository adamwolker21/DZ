package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now only contains StreamHG to focus on it.
val extractorList = listOf(
    StreamHG()
)

// Using a mobile User-Agent as it might be treated differently by the server.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("StreamHG_Final", "safeGetAsDocument FAILED for $url: ${e.message}")
        null
    }
}

// The classes must be public to be accessible in the public `extractorList`.
abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // Focusing only on kravaxxa.com
    private val potentialHosts = listOf(
        "kravaxxa.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("StreamHG_Final", "================== getUrl CALLED v28 ==================")
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("StreamHG_Final", "Failed to extract video ID.")
            return
        }

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d("StreamHG_Final", "Constructed final page URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue
            Log.d("StreamHG_Final", "Successfully retrieved document.")

            // =================== v28 THE SMART FIX ===================
            // The server has two 'eval' scripts. One is a short anti-bot script (the trap),
            // and the other is the long, real one with the video links (the treasure).
            // Instead of finding the *first* script, we find *all* of them and pick the longest one.
            val packedJs = doc.select("script")
                .map { it.data() }
                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                .maxByOrNull { it.length } // ← Find the longest script
            // =========================================================

            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Final", "Could not find any packed JS (eval) script.")
                continue
            }
            Log.d("StreamHG_Final", "Found the longest packed JS script (length: ${packedJs.length}). Attempting to unpack...")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Final", "Successfully unpacked JS.")
                
                // Using the robust string manipulation to find the hls2 link.
                val m3u8Link = unpacked.substringAfter("\"hls2\":\"").substringBefore("\"")

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    Log.d("StreamHG_Final", "SUCCESS: Found 'hls2' link: $m3u8Link")
                    
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    Log.d("StreamHG_Final", "Successfully submitted the link via callback.")
                    return 
                } else {
                    Log.e("StreamHG_Final", "String manipulation FAILED to find a valid 'hls2' link in the unpacked JS.")
                }

            } catch (e: Exception) {
                Log.e("StreamHG_Final", "An error occurred during unpacking or link extraction: ${e.message}")
            }
        }
        Log.d("StreamHG_Final", "================== getUrl FINISHED (No link found) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
