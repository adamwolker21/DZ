package com.egydead

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.JsUnpacker
import android.util.Log
import org.json.JSONObject

// القائمة الآن تحتوي فقط على السيرفر المستهدف
val extractorList = listOf(
    Earnvids()
)

// نحتفظ بـ cloudflareKiller لأنه ضروري
private val cloudflareKiller by lazy { CloudflareKiller() }

// =========================================================================
//  الكود الأساسي لتشغيل سيرفرات Packed (مثل Earnvids)
// =========================================================================
open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val logTag = "PackedExtractor"

    /**
     * Searches for a video link in the unpacked JavaScript using multiple known patterns.
     */
    private fun findUrlInUnpackedJs(unpackedJs: String): String? {
        // Pattern 1: Search for "hls2": "..."
        Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'hls2' JSON key pattern.")
            return it
        }

        // Pattern 2: Generic search for any m3u8 or mp4 link
        Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with generic m3u8/mp4 pattern.")
            return it
        }

        // Pattern 3: Search for file: "..."
        Regex("""file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'file:' pattern.")
            return it
        }
        
        Log.e(logTag, "[$name] FAILED: Could not find a video link with any known pattern.")
        return null
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.d(logTag, "Extractor '$name' started for URL: $url")
        try {
            // Get page content with Cloudflare protection
            val playerPageContent = app.get(url, referer = referer, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) {
                Log.e(logTag, "[$name] FAILED: Page content is empty.")
                return null
            }

            // Unpack the JavaScript
            val unpackedJs = JsUnpacker(playerPageContent).unpack()
            if (unpackedJs == null) {
                 Log.e(logTag, "[$name] FAILED: JsUnpacker returned null.")
                 return null
            }

            // Find the video link within the unpacked script
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null
            
            // Add necessary headers for the player
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
            
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

// تعريف سيرفر Earnvids باستخدام الكود الأساسي
class Earnvids : PackedExtractorBase("Earnvids", "dingtezuni.com")
