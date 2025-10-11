package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
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
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/5.0 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
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

            val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Final", "Could not find the packed JS (eval) script on the page.")
                continue
            }
            Log.d("StreamHG_Final", "Found packed JS script.")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Final", "Successfully unpacked JS.")
                
                // Flexible regex to find the hls2 link, ignoring formatting.
                val m3u8Link = Regex("""['"]hls2['"]\s*:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1)

                if (m3u8Link != null) {
                    Log.d("StreamHG_Final", "SUCCESS: Found 'hls2' link with flexible regex: $m3u8Link")
                    Log.d("StreamHG_Final", "Submitting link directly using the deprecated ExtractorLink constructor.")
                    
                    // =================== FINAL FIX IMPLEMENTATION ===================
                    // Use the old constructor and suppress the deprecation warning
                    // to ensure a successful build while including the referer.
                    @Suppress("DEPRECATION")
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Link,
                            referer = finalPageUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    Log.d("StreamHG_Final", "Successfully submitted the link via callback.")
                    return 
                } else {
                    Log.e("StreamHG_Final", "Unpacked JS, but the final regex FAILED to find the 'hls2' link.")
                }

            } catch (e: Exception) {
                Log.e("StreamHG_Final", "An error occurred during unpacking or regex matching: ${e.message}")
            }
        }

        Log.d("StreamHG_Final", "================== getUrl FINISHED (No link found) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
