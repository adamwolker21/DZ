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

// =================== v27 CHANGE: Mobile User-Agent ===================
// Changed the User-Agent to mimic a mobile browser.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    Log.d("StreamHG_Forensics", "safeGetAsDocument: Attempting to GET URL: $url")
    return try {
        val response = app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false)
        Log.d("StreamHG_Forensics", "safeGetAsDocument: Successfully got response for URL: $url with status code: ${response.code}")
        
        // =================== v27 CHANGE: Log Phase 2 Content ===================
        val htmlContent = response.text
        Log.d("StreamHG_Forensics", "Now logging the full HTML content received (Phase 2)...")
        if (htmlContent.length > 4000) {
            Log.d("Phase2_HTML_Content", "Content is long, logging in chunks:")
            htmlContent.chunked(4000).forEachIndexed { index, chunk ->
                Log.d("Phase2_HTML_Content", "Chunk ${index + 1}: $chunk")
            }
        } else {
            Log.d("Phase2_HTML_Content", htmlContent)
        }
        Log.d("StreamHG_Forensics", "Finished logging Phase 2 content.")
        // =====================================================================

        response.document
    } catch (e: Exception) {
        Log.e("StreamHG_Forensics", "safeGetAsDocument: FAILED to GET URL: $url. Error: ${e.message}")
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
        Log.d("StreamHG_Forensics", "================== getUrl CALLED ==================")
        Log.d("StreamHG_Forensics", "Initial URL: $url")

        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("StreamHG_Forensics", "Failed to extract video ID from URL.")
            return
        }
        Log.d("StreamHG_Forensics", "Extracted Video ID: $videoId")

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d("StreamHG_Forensics", "Constructed final page URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url)

            if (doc == null) {
                Log.e("StreamHG_Forensics", "Failed to get document from $finalPageUrl. Document is null.")
                continue
            }
            Log.d("StreamHG_Forensics", "Successfully retrieved document.")

            val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Forensics", "Could not find the packed JS (eval) script on the page.")
                continue
            }
            Log.d("StreamHG_Forensics", "Found packed JS script. Attempting to unpack (Phase 3)...")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Forensics", "Successfully unpacked JS. Now logging its content (Phase 3)...")

                // Log the entire unpacked content in chunks to avoid truncation.
                if (unpacked.length > 4000) {
                    Log.d("Phase3_Unpacked_JS", "Content is long, logging in chunks:")
                    unpacked.chunked(4000).forEachIndexed { index, chunk ->
                        Log.d("Phase3_Unpacked_JS", "Chunk ${index + 1}: $chunk")
                    }
                } else {
                    Log.d("Phase3_Unpacked_JS", unpacked)
                }
                Log.d("StreamHG_Forensics", "Finished logging Phase 3 content.")
                
                val m3u8Link = unpacked.substringAfter("\"hls2\":\"").substringBefore("\"")

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    Log.d("StreamHG_Forensics", "SUCCESS: Found 'hls2' link: $m3u8Link")
                    
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
                    Log.d("StreamHG_Forensics", "Successfully submitted the link via callback.")
                    return 
                } else {
                    Log.e("StreamHG_Forensics", "String manipulation FAILED to find a valid 'hls2' link in Phase 3 content.")
                }

            } catch (e: Exception) {
                Log.e("StreamHG_Forensics", "An error occurred during unpacking or link extraction: ${e.message}")
            }
        }

        Log.d("StreamHG_Forensics", "================== getUrl FINISHED (No link found) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
