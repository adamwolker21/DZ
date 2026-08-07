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

// سيرفر Ult4vid منظف من السجلات (Logs)
class Ult4vid : ExtractorApi() {
    override var name = "Ult4vid"
    override var mainUrl = "ult4vid.one"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5"
            )
            
            val responseText = app.get(url, headers = headers, referer = referer ?: url, interceptor = cloudflareKiller).text
            
            var sourceUrlRaw = Regex("""<source[^>]+src=["']([^"']+)["']""").find(responseText)?.groupValues?.get(1)
            
            if (sourceUrlRaw.isNullOrBlank()) {
                sourceUrlRaw = Regex("""(https?://[^"']+\.r2\.cloudflarestorage\.com[^"']+)""").find(responseText)?.groupValues?.get(1)
            }

            if (!sourceUrlRaw.isNullOrBlank()) {
                val cleanUrl = sourceUrlRaw.replace("&amp;", "&").replace("&#038;", "&")
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
            }
        } catch (e: Exception) {
            // صامت
        }
        return null
    }
}

// المستخرج الجديد الخاص بـ 71stream (مع تفعيل سجلات التتبع)
class Stream71 : ExtractorApi() {
    override var name = "71stream"
    override var mainUrl = "71stream.one"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            Log.d("Stream71Extractor", "Fetching URL: $url")
            
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            
            // جلب النص الخام للصفحة لتجنب فقدان البيانات
            val responseText = app.get(url, headers = headers, referer = referer ?: url, interceptor = cloudflareKiller).text
            
            // البحث عن بيانات الـ JSON باستخدام Regex لتفادي مشاكل Jsoup مع النصوص الضخمة
            var dataPageContent = Regex("""data-page=(['"])(.*?)\1""").find(responseText)?.groupValues?.get(2)
            
            if (!dataPageContent.isNullOrBlank()) {
                Log.d("Stream71Extractor", "Data found via Regex.")
                dataPageContent = dataPageContent.replace("&quot;", "\"").replace("&amp;", "&")
            } else {
                Log.d("Stream71Extractor", "Regex failed, trying JSoup...")
                val document = org.jsoup.Jsoup.parse(responseText)
                dataPageContent = document.selectFirst("#app")?.attr("data-page")
            }

            if (dataPageContent.isNullOrBlank()) {
                Log.e("Stream71Extractor", "Could not find data-page attribute in #app")
                return null
            }
            
            Log.d("Stream71Extractor", "Successfully extracted data-page JSON. Parsing...")

            val jsonObject = JSONObject(dataPageContent)
            val props = jsonObject.optJSONObject("props")
            if (props == null) {
                Log.e("Stream71Extractor", "Could not find 'props' object in JSON")
                return null
            }
            
            val qualitiesArray = props.optJSONArray("qualities")
            if (qualitiesArray == null) {
                Log.e("Stream71Extractor", "Could not find 'qualities' array in props")
                return null
            }

            Log.d("Stream71Extractor", "Found ${qualitiesArray.length()} qualities")

            for (i in 0 until qualitiesArray.length()) {
                val qualityItem = qualitiesArray.optJSONObject(i) ?: continue
                val rawUrl = qualityItem.optString("url")
                val label = qualityItem.optString("label") // مثال: 720p, 480p, Original

                Log.d("Stream71Extractor", "Processing quality: $label")

                if (rawUrl.isNotBlank()) {
                    val cleanUrl = rawUrl.replace("&amp;", "&").replace("&#038;", "&")
                    
                    val qualityValue = when {
                        label.contains("Original", ignoreCase = true) || label.contains("1080") -> Qualities.P1080.value
                        label.contains("720") -> Qualities.P720.value
                        label.contains("480") -> Qualities.P480.value
                        label.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    extractedLinks.add(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} $label",
                            url = cleanUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = qualityValue
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Stream71Extractor", "Error during extraction: ${e.message}")
        }
        
        Log.d("Stream71Extractor", "Total extracted links: ${extractedLinks.size}")
        return if (extractedLinks.isNotEmpty()) extractedLinks else null
    }
}
