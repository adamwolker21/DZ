package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// قائمة المستخرجات تحتوي فقط على StreamHG للتركيز عليه
val extractorList = listOf(
    StreamHG()
)

// ترويسات متصفح كاملة للمحاكاة
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// دالة آمنة لجلب الصفحة كـ Document
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    Log.d("StreamHG_Debug", "safeGetAsDocument: Attempting to GET URL: $url")
    return try {
        val response = app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false)
        Log.d("StreamHG_Debug", "safeGetAsDocument: Successfully got response for URL: $url with status code: ${response.code}")
        response.document
    } catch (e: Exception) {
        Log.e("StreamHG_Debug", "safeGetAsDocument: FAILED to GET URL: $url. Error: ${e.message}")
        null
    }
}

// =================== FIX: Removed 'private' keyword ===================
// The classes must be public to be accessible in the public `extractorList`.
abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // التركيز فقط على kravaxxa.com
    private val potentialHosts = listOf(
        "kravaxxa.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("StreamHG_Debug", "================== getUrl CALLED ==================")
        Log.d("StreamHG_Debug", "Initial URL: $url")
        Log.d("StreamHG_Debug", "Referer: $referer")

        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("StreamHG_Debug", "Failed to extract video ID from URL.")
            return
        }
        Log.d("StreamHG_Debug", "Extracted Video ID: $videoId")

        // The loop will only run once for kravaxxa.com
        for (host in potentialHosts) {
            Log.d("StreamHG_Debug", "Trying host: $host")
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d("StreamHG_Debug", "Constructed final page URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url)

            if (doc == null) {
                Log.e("StreamHG_Debug", "Failed to get document from $finalPageUrl. Document is null.")
                continue // In this case, it will just end the loop
            }
            Log.d("StreamHG_Debug", "Successfully retrieved document. Title: ${doc.title()}")

            val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Debug", "Could not find the packed JS (eval) script on the page.")
                continue
            }
            Log.d("StreamHG_Debug", "Found packed JS script. Length: ${packedJs.length}")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Debug", "Successfully unpacked JS. Unpacked content length: ${unpacked.length}")
                
                // This regex was from v13, which we identified as potentially incorrect.
                // We keep it for now as per the base version.
                val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)

                if (m3u8Link != null) {
                    Log.d("StreamHG_Debug", "SUCCESS: Found m3u8 link in unpacked JS: $m3u8Link")
                    Log.d("StreamHG_Debug", "Calling M3u8Helper.generateM3u8...")
                    M3u8Helper.generateM3u8(
                        this.name,
                        m3u8Link,
                        finalPageUrl, // Referer for the m3u8 request
                        headers = BROWSER_HEADERS
                    ).forEach { link ->
                        Log.d("StreamHG_Debug", "M3u8Helper provided a link: ${link.url} with quality: ${link.quality}")
                        callback(link)
                    }
                    Log.d("StreamHG_Debug", "Finished calling M3u8Helper.")
                    return // Exit immediately after success
                } else {
                    Log.e("StreamHG_Debug", "Unpacked JS, but the regex did not find a master.m3u8 link.")
                }

            } catch (e: Exception) {
                Log.e("StreamHG_Debug", "An error occurred during unpacking or regex matching: ${e.message}")
            }
        }

        Log.d("StreamHG_Debug", "================== getUrl FINISHED (No link found) ==================")
    }
}

// =================== FIX: Removed 'private' keyword ===================
class StreamHG : StreamHGBase("StreamHG", "hglink.to")
