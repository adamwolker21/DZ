package com.egydead

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.SubtitleFile // ✅ تم التأكد من وجود هذا الاستيراد
import android.util.Log
import org.json.JSONObject

// القائمة الآن تحتوي فقط على السيرفر المستهدف
val extractorList = listOf(
    Earnvids()
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// =========================================================================
//  الكود الأساسي وقد تم تعديله ليتوافق مع البروفايدر
// =========================================================================
open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val logTag = "PackedExtractor"

    private fun findUrlInUnpackedJs(unpackedJs: String): String? {
        Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'hls2' JSON key pattern.")
            return it
        }
        Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with generic m3u8/mp4 pattern.")
            return it
        }
        Regex("""file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let {
            Log.d(logTag, "[$name] SUCCESS: Found link with 'file:' pattern.")
            return it
        }
        Log.e(logTag, "[$name] FAILED: Could not find a video link with any known pattern.")
        return null
    }

    // ✅  تم تعديل هذه الدالة بالكامل لتعود إلى استخدام نظام الـ callback
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(logTag, "Extractor '$name' started for URL: $url")
        try {
            val playerPageContent = app.get(url, referer = referer, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) {
                Log.e(logTag, "[$name] FAILED: Page content is empty.")
                return // الخروج عند الفشل
            }

            val unpackedJs = JsUnpacker(playerPageContent).unpack()
            if (unpackedJs == null) {
                 Log.e(logTag, "[$name] FAILED: JsUnpacker returned null.")
                 return // الخروج عند الفشل
            }

            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return // الخروج عند الفشل
            
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
            
            Log.d(logTag, "[$name] Process finished successfully. Invoking callback with the link.")
            
            // ✅  استخدام callback لإرسال الرابط بدلاً من return
            callback(
                newExtractorLink(source = this.name, name = this.name, url = finalUrlWithHeaders) {
                    this.referer = url
                }
            )
        } catch (e: Exception) {
            Log.e(logTag, "[$name] An unexpected error occurred: ${e.message}", e)
        }
    }
}

// تعريف سيرفر Earnvids - لا تغيير هنا
class Earnvids : PackedExtractorBase("Earnvids", "dingtezuni.com")
