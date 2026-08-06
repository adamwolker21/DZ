package com.fivetv

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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
    override var name = "Morencius"
    override var mainUrl = "morencius.com" 
    override val requiresReferer = true
    private val logTag = "EarnvidsExtractor"
    private val potentialHosts = listOf("https://morencius.com", "https://earnvids.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return null

        for (host in potentialHosts) {
            try {
                val finalPageUrl = if (url.contains("/e/")) "$host/e/$videoId" else "$host/v/$videoId"
                
                val playerPageContent = app.get(finalPageUrl, referer = referer ?: url, interceptor = cloudflareKiller).text
                if (playerPageContent.isBlank()) continue

                val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: continue
                val videoLink = findUrlInUnpackedJs(unpackedJs) ?: continue

                val headers = mapOf("Referer" to finalPageUrl, "User-Agent" to USER_AGENT)
                val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
                
                val isM3u8 = videoLink.contains(".m3u8")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrlWithHeaders,
                        type = linkType
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.Unknown.value
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
    override var name = "Hgcloud"
    override var mainUrl = "hgcloud.to"
    override val requiresReferer = true
    private val logTag = "StreamHGExtractor"
    private val potentialHosts = listOf("https://hgcloud.to", "https://vibuxer.com", "https://hanerix.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(logTag, "Failed to extract video ID from $url")
            return null
        }

        for (host in potentialHosts) {
            try {
                val finalPageUrl = "$host/e/$videoId"
                
                val playerPageContent = app.get(finalPageUrl, referer = url, interceptor = cloudflareKiller).text
                if (playerPageContent.isBlank()) continue

                val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: continue
                val videoLink = findUrlInUnpackedJs(unpackedJs) ?: continue

                val headers = mapOf("Referer" to finalPageUrl, "User-Agent" to USER_AGENT)
                val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

                val isM3u8 = videoLink.contains(".m3u8")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrlWithHeaders,
                        type = linkType
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                Log.e(logTag, "Failed to extract from host $host. Error: ${e.message}")
            }
        }
        return null
    }
}

// مستخرج سيرفر Ult4vid الجديد
class Ult4vid : ExtractorApi() {
    override var name = "Ult4vid"
    override var mainUrl = "ult4vid.one"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // جلب صفحة المشاهدة
            val response = app.get(url, referer = referer, interceptor = cloudflareKiller).document
            
            // استخراج رابط الـ mp4 من داخل وسم <source> المتواجد في <video>
            val sourceUrl = response.selectFirst("video source")?.attr("src")
                ?: Regex("""<source\s+src=["']([^"']+)["']""").find(response.html())?.groupValues?.get(1)

            if (!sourceUrl.isNullOrBlank()) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = sourceUrl,
                        type = ExtractorLinkType.VIDEO // التحديد كـ VIDEO لأنها صيغة mp4 مباشرة
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Ult4vidExtractor", "Error: ${e.message}")
        }
        return null
    }
}
