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

// Full browser headers for emulation.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    Log.d("StreamHG_Final", "safeGetAsDocument: Attempting to GET URL: $url")
    return try {
        val response = app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false)
        Log.d("StreamHG_Final", "safeGetAsDocument: Successfully got response for URL: $url with status code: ${response.code}")
        response.document
    } catch (e: Exception) {
        Log.e("StreamHG_Final", "safeGetAsDocument: FAILED to GET URL: $url. Error: ${e.message}")
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
        Log.d("StreamHG_Final", "================== getUrl CALLED ==================")
        Log.d("StreamHG_Final", "Initial URL: $url")

        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("StreamHG_Final", "Failed to extract video ID from URL.")
            return
        }
        Log.d("StreamHG_Final", "Extracted Video ID: $videoId")

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d("StreamHG_Final", "Constructed final page URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url)

            if (doc == null) {
                Log.e("StreamHG_Final", "Failed to get document from $finalPageUrl. Document is null.")
                continue
            }
            Log.d("StreamHG_Final", "Successfully retrieved document.")

            val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Final", "Could not find the packed JS (eval) script on the page.")
                continue
            }
            Log.d("StreamHG_Final", "Found packed JS script.")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Final", "Successfully unpacked JS.")
                
                // =================== v25 FIX: Correct String Manipulation ===================
                // This is the corrected version of the string search. It looks for the literal
                // text `"hls2":"` and then extracts everything until the next `"`.
                val m3u8Link = unpacked.substringAfter("\"hls2\":\"").substringBefore("\"")

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    Log.d("StreamHG_Final", "SUCCESS: Found 'hls2' link with CORRECT string manipulation: $m3u8Link")
                    Log.d("StreamHG_Final", "Submitting link using the correct newExtractorLink syntax.")
                    
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
                    Log.e("StreamHG_Final", "Corrected string manipulation FAILED to find a valid 'hls2' link. Check unpacked JS content.")
                }

            } catch (e: Exception) {
                Log.e("StreamHG_Final", "An error occurred during unpacking or link extraction: ${e.message}")
            }
        }

        Log.d("StreamHG_Final", "================== getUrl FINISHED (No link found) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
