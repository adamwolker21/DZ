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
//  StreamHG CODE (No changes)
// =========================================================================

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)
private val cloudflareKiller by lazy { CloudflareKiller() }
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? { /* ... No changes here ... */ return null }
abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() { /* ... No changes here ... */ }
class StreamHG : StreamHGBase("StreamHG", "hglink.to")


// =========================================================================
//  Packed Extractors (Vidshare, Earnvids) - WITH SCRIPT LOGGING
// =========================================================================

open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val logTag = "PackedExtractor"

    private fun findUrlInUnpackedJs(unpackedJs: String): String? {
        Log.d(logTag, "[$name] Searching for video link using multiple patterns...")
        Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with original pattern (m3u8/mp4).")
            return it
        }
        Log.d(logTag, "[$name] INFO: Original pattern failed.")
        Regex("""file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'file:' pattern.")
            return it
        }
        Log.d(logTag, "[$name] INFO: 'file:' pattern failed.")
         Regex("""source\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'source:' pattern.")
            return it
        }
        Log.d(logTag, "[$name] INFO: 'source:' pattern failed.")
        return null
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.d(logTag, "Extractor '$name' started for URL: $url")
        try {
            // STAGE 1: Get Page Content
            Log.d(logTag, "[$name] Attempting to GET page content...")
            val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
            if (playerPageContent.isBlank()) {
                Log.e(logTag, "[$name] FAILED: Page content is empty.")
                return null
            }
            Log.d(logTag, "[$name] SUCCESS: Page content retrieved.")

            // STAGE 2: Unpack JavaScript
            Log.d(logTag, "[$name] Attempting to unpack JavaScript...")
            val unpackedJs = JsUnpacker(playerPageContent).unpack()
            if (unpackedJs == null) {
                 Log.e(logTag, "[$name] FAILED: JsUnpacker returned null.")
                 return null
            }
            Log.d(logTag, "[$name] SUCCESS: JS unpacked.")

            // ✅  المرحلة المطلوبة: طباعة السكريبت الذي تم فك تشفيره
            Log.d(logTag, "[$name] --- UNPACKED SCRIPT CONTENT (START) ---")
            // سيقوم Logcat تلقائيًا بتقسيم النص الطويل إلى عدة أسطر
            Log.d(logTag, unpackedJs) 
            Log.d(logTag, "[$name] --- UNPACKED SCRIPT CONTENT (END) ---")

            // STAGE 3: Find Video Link in the Unpacked Script
            val videoLink = findUrlInUnpackedJs(unpackedJs)
            if (videoLink == null) {
                Log.e(logTag, "[$name] FAILED: Could not find a video link with any of the known patterns.")
                return null
            }
            Log.d(logTag, "[$name] SUCCESS: Found video link: $videoLink")

            // STAGE 4: Construct Final URL with Headers
            Log.d(logTag, "[$name] Constructing final URL with headers...")
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val headersJson = JSONObject(headers).toString()
            val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
            Log.d(logTag, "[$name] Final URL created: $finalUrlWithHeaders")

            // STAGE 5: Return the Link
            Log.d(logTag, "[$name] Process finished successfully. Returning link.")
            return listOf(
                newExtractorLink(source = this.name, name = this.name, url = finalUrlWithHeaders) {
                    this.referer = url
                }
            )
        } catch (e: Exception) {
            Log.e(logTag, "[$name] An unexpected error occurred: ${e.message}", e)
            return null
        }
    }
}

class Vidshare : PackedExtractorBase("Vidshare", "1vid1shar.com")
class Earnvids : PackedExtractorBase("Earnvids", "dingtezuni.com")
