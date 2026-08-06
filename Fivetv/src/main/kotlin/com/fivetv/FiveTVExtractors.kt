package com.fivetv

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

private val cloudflareKiller by lazy { CloudflareKiller() }

// دالة مساعدة للبحث عن روابط الفيديو بعد فك التشفير
private fun findUrlInUnpackedJs(unpackedJs: String): String? {
    Regex("""(?i)"hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    return null
}

// مستخرج سيرفر Morencius / Earnvids
class Earnvids : ExtractorApi() {
    override var name = "Morencius (Earnvids)"
    override var mainUrl = "morencius.com" 
    override val requiresReferer = true
    private val logTag = "EarnvidsExtractor"
    private val potentialHosts = listOf("https://morencius.com", "https://earnvids.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return null

        for (host in potentialHosts) {
            try {
                // الكود يدعم كلاً من صيغة embed (حرف e) وصيغة player (حرف v)
                val finalPageUrl = if (url.contains("/e/")) "$host/e/$videoId" else "$host/v/$videoId"
                
                val playerPageContent = app.get(finalPageUrl, referer = referer ?: url, interceptor = cloudflareKiller).text
                if (playerPageContent.isBlank()) continue

                val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: continue
                val videoLink = findUrlInUnpackedJs(unpackedJs) ?: continue

                val headers = mapOf("Referer" to finalPageUrl, "User-Agent" to USER_AGENT)
                val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

                return listOf(
                    newExtractorLink(source = "Morencius", name = "Morencius", url = finalUrlWithHeaders, type = ExtractorLinkType.M3U8) {
                        this.referer = finalPageUrl
                    }
                )
            } catch (e: Exception) {
                Log.e(logTag, "Failed to extract from host $host. Error: ${e.message}")
            }
        }
        return null
    }
}

// مستخرج سيرفر Hgcloud / StreamHG
class StreamHG : ExtractorApi() {
    override var name = "Hgcloud (StreamHG)"
    override var mainUrl = "hgcloud.com"
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // محاولة تحويل رابط المشاهدة العادي إلى رابط Embed لتسهيل جلب البيانات
            val embedUrl = url.replace("/v/", "/e/").replace("/watch?v=", "/e/")
            
            val response = app.get(embedUrl, referer = referer, interceptor = cloudflareKiller)
            val unpackedJs = JsUnpacker(response.text).unpack() ?: response.text
            val videoLink = findUrlInUnpackedJs(unpackedJs)
            
            if (videoLink != null) {
                return listOf(
                    newExtractorLink(source = "Hgcloud", name = "Hgcloud", url = videoLink, referer = embedUrl, type = ExtractorLinkType.M3U8)
                )
            }
        } catch (e: Exception) {
            Log.e("StreamHGExtractor", "Error: ${e.message}")
        }
        return null
    }
}
