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

private fun findUrlInUnpackedJs(unpackedJs: String): String? {
    Regex("""(?i)"hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    return null
}

class Earnvids : ExtractorApi() {
    override var name = "Morencius"
    override var mainUrl = "morencius.com" 
    override val requiresReferer = true
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
                Log.e("EarnvidsExtractor", "Error: ${e.message}")
            }
        }
        return null
    }
}

class StreamHG : ExtractorApi() {
    override var name = "Hgcloud"
    override var mainUrl = "hgcloud.to"
    override val requiresReferer = true
    private val potentialHosts = listOf("https://hgcloud.to", "https://vibuxer.com", "https://hanerix.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return null

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
                Log.e("StreamHGExtractor", "Error: ${e.message}")
            }
        }
        return null
    }
}

class Ult4vid : ExtractorApi() {
    override var name = "Ult4vid"
    override var mainUrl = "ult4vid.one"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("Ult4vidExtractor", "Fetching URL: $url")
            
            // إضافة هيدرز متكاملة لضمان استجابة الموقع بشكل صحيح بدلاً من الحجب
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5"
            )
            
            val responseText = app.get(url, headers = headers, referer = referer ?: url, interceptor = cloudflareKiller).text
            
            // البحث عن الرابط بطريقتين: وسم source أو دومين cloudflarestorage مباشرة
            var sourceUrlRaw = Regex("""<source[^>]+src=["']([^"']+)["']""").find(responseText)?.groupValues?.get(1)
            
            if (sourceUrlRaw.isNullOrBlank()) {
                sourceUrlRaw = Regex("""(https?://[^"']+\.r2\.cloudflarestorage\.com[^"']+)""").find(responseText)?.groupValues?.get(1)
            }

            if (!sourceUrlRaw.isNullOrBlank()) {
                Log.d("Ult4vidExtractor", "Raw URL found: $sourceUrlRaw")
                
                // تنظيف الرابط من كود الـ HTML Entities بدقة
                val cleanUrl = sourceUrlRaw.replace("&amp;", "&").replace("&#038;", "&")
                Log.d("Ult4vidExtractor", "Cleaned URL: $cleanUrl")

                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                Log.e("Ult4vidExtractor", "Could not find video URL in HTML.")
            }
        } catch (e: Exception) {
            Log.e("Ult4vidExtractor", "Error during extraction: ${e.message}")
        }
        return null
    }
}
