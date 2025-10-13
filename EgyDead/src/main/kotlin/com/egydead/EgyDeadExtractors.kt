package com.egydead

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.JsUnpacker
import android.util.Log
import org.json.JSONObject

// ✅  تمت إضافة StreamHG إلى القائمة
val extractorList = listOf(
    Earnvids(),
    StreamHG()
)

private val cloudflareKiller by lazy { CloudflareKiller() }

/**
 * A shared function to find the video URL within unpacked JavaScript.
 * It tries multiple patterns for better compatibility.
 */
private fun findUrlInUnpackedJs(unpackedJs: String): String? {
    // Pattern 1: Search for "hls2": "..." (Most common for these servers)
    Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    // Pattern 2: Generic search for any m3u8 or mp4 link
    Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    // Pattern 3: Search for file: "..."
    Regex("""file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    return null
}

// =========================================================================
//  Extractor for Earnvids (and similar servers)
// =========================================================================
class Earnvids : ExtractorApi() {
    override var name = "Earnvids"
    override var mainUrl = "dingtezuni.com"
    override val requiresReferer = true
    private val logTag = "EarnvidsExtractor"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val playerPageContent = app.get(url, referer = referer, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) return null

            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null
            
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
            
            return listOf(
                newExtractorLink(source = this.name, name = this.name, url = finalUrlWithHeaders, type = ExtractorLinkType.M3U8) {
                    this.referer = url
                }
            )
        } catch (e: Exception) {
            Log.e(logTag, "An error occurred: ${e.message}", e)
            return null
        }
    }
}

// =========================================================================
//  ✅  المستخرِج الجديد والمنفصل لسيرفر StreamHG
// =========================================================================
class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true
    private val logTag = "StreamHGExtractor"
    private val host = "https://kravaxxa.com"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // الخطوة 1 و 2: استخلاص المعرف وبناء الرابط الجديد
            val videoId = url.substringAfterLast("/")
            if (videoId.isBlank()) {
                Log.e(logTag, "Failed to extract video ID from $url")
                return null
            }
            val finalPageUrl = "$host/e/$videoId"
            Log.d(logTag, "Constructed final URL: $finalPageUrl")

            // الخطوة 3 و 4: جلب الصفحة، فك التشفير، وإيجاد الرابط
            val playerPageContent = app.get(finalPageUrl, referer = url, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) return null

            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null

            val headers = mapOf("Referer" to finalPageUrl, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

            return listOf(
                newExtractorLink(source = this.name, name = this.name, url = finalUrlWithHeaders, type = ExtractorLinkType.M3U8) {
                    this.referer = finalPageUrl
                }
            )
        } catch (e: Exception) {
            Log.e(logTag, "An error occurred: ${e.message}", e)
            return null
        }
    }
}
