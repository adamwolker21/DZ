package com.egydead

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.jsoup.nodes.Document
import android.util.Log
import org.json.JSONObject

val extractorList = listOf(
    StreamHG(),
    Vidshare(),
    Earnvids()
)

// =========================================================================
//  StreamHG CODE (No changes here)
// =========================================================================

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

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

abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val potentialHosts = listOf("kravaxxa.com")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return
        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue
            val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data() ?: continue
            try {
                val unpacked = getAndUnpack(packedJs)
                val m3u8Link = unpacked.substringAfter("""hls2":"_URL_""").substringBefore("""_URL_"""")
                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    callback(
                        newExtractorLink(source = this.name, name = this.name, url = m3u8Link, type = ExtractorLinkType.M3U8) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return 
                }
            } catch (e: Exception) {
                Log.e("StreamHG_Final", "An error occurred during unpacking or link extraction: ${e.message}")
            }
        }
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")


// =========================================================================
//  ✅ Packed Extractors (Vidshare, Earnvids) WITH DETAILED LOGGING
// =========================================================================

open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // A consistent tag to easily filter logs for these types of extractors
    private val logTag = "PackedExtractor"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Log when the process starts for a specific server
        Log.d(logTag, "Extractor '$name' started for URL: $url")

        try {
            // === STAGE 1: Get Page Content ===
            Log.d(logTag, "[$name] Attempting to GET page content...")
            val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
            
            if (playerPageContent.isBlank()) {
                Log.e(logTag, "[$name] FAILED: Page content is empty or couldn't be retrieved.")
                return null
            }
            Log.d(logTag, "[$name] SUCCESS: Page content retrieved successfully.")

            // === STAGE 2: Unpack JavaScript ===
            Log.d(logTag, "[$name] Attempting to unpack JavaScript...")
            val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
                Log.d(logTag, "[$name] SUCCESS: JS unpacked. Now searching for video link...")
                
                // === STAGE 3: Find Video Link with Regex ===
                Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
            }

            if (videoLink == null) {
                Log.e(logTag, "[$name] FAILED: Could not find a video link (m3u8/mp4) in the unpacked JS.")
                return null
            }
            Log.d(logTag, "[$name] SUCCESS: Found video link: $videoLink")

            // === STAGE 4: Construct Final URL with Headers ===
            Log.d(logTag, "[$name] Constructing final URL with headers...")
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val headersJson = JSONObject(headers).toString()
            val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
            Log.d(logTag, "[$name] Final URL created: $finalUrlWithHeaders")

            // === STAGE 5: Return the Link ===
            Log.d(logTag, "[$name] Process finished successfully. Returning link.")
            return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrlWithHeaders,
                ) {
                    this.referer = url
                }
            )

        } catch (e: Exception) {
            // This will catch any unexpected errors during the process (e.g., network failure)
            Log.e(logTag, "[$name] An unexpected error occurred: ${e.message}", e)
            return null
        }
    }
}

class Vidshare : PackedExtractorBase("Vidshare", "1vid1shar.com")
class Earnvids : PackedExtractorBase("Earnvids", "dingtezuni.com")
