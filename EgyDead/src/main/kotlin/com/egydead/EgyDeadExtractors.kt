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
//  StreamHG CODE
// =========================================================================
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
class kravaxxa : StreamHGBase("kravaxxa", "kravaxxa.com")
// Helper functions (common)
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)
private val cloudflareKiller by lazy { CloudflareKiller() }
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("StreamHG_Final", "safeGetAsDocument FAILED for $url. Error: ${e.message}")
        null
    }
}


// =========================================================================
//  Packed Extractors (Vidshare, Earnvids) - WITH CLOUDFLARE PROTECTION
// =========================================================================

open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val logTag = "PackedExtractor"

    // ... (findUrlInUnpackedJs function is unchanged) ...
    private fun findUrlInUnpackedJs(unpackedJs: String): String? {
        Log.d(logTag, "[$name] Searching for video link...")
        Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'hls2' JSON key pattern.")
            return it
        }
        // ... (other patterns)
        return null
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.d(logTag, "Extractor '$name' started for URL: $url")
        try {
            Log.d(logTag, "[$name] Attempting to GET page content with Cloudflare protection...")
            
            // ✅  هنا تم التعديل اللازم: إضافة interceptor = cloudflareKiller
            val playerPageContent = app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT),
                interceptor = cloudflareKiller // <-- This is the crucial fix
            ).text

            if (playerPageContent.isBlank()) {
                Log.e(logTag, "[$name] FAILED: Page content is empty after Cloudflare.")
                return null
            }
            Log.d(logTag, "[$name] SUCCESS: Page content retrieved.")
            Log.d(logTag, "[$name] --- RAW HTML CONTENT (START) ---")
            Log.d(logTag, playerPageContent)
            Log.d(logTag, "[$name] --- RAW HTML CONTENT (END) ---")

            // ... (rest of the function is unchanged) ...
            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
            
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
