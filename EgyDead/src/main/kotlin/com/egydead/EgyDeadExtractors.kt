package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// قائمة المستخرجات المستخدمة من قبل المزود
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(), Dumbalag(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
    EarnVids(),
    VidGuard()
)

// ترويسات متصفح كاملة للمحاكاة
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// دالة آمنة لجلب الصفحة كـ Document
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("SafeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// دالة آمنة لجلب الصفحة كنص
private suspend fun safeGetAsText(url: String, referer: String? = null): String? {
     return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).text
    } catch (e: Exception) {
        Log.e("SafeGetAsText", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// ===== StreamHGBase: الطريقة 1 - التوقيع الأساسي =====
private abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    private val potentialHosts = listOf(
        "kravaxxa.com",
        "cavanhabg.com",
        "dumbalag.com",
        "davioad.com",
        "haxloppd.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url)

            val packedJs = doc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs != null) {
                val unpacked = getAndUnpack(packedJs)
                val relativeLink = Regex("""hls4"\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)

                if (relativeLink != null) {
                    val fullUrl = "https://$host$relativeLink"
                    Log.d(name, "Found final m3u8 link: $fullUrl")
                    
                    // الطريقة 1: التوقيع الأساسي مع quality كمعامل
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - HLS",
                            url = fullUrl,
                            quality = Qualities.Unknown.value,
                        ) {
                            referer = finalPageUrl
                            isM3u8 = true
                        }
                    )
                    return
                }
            }
        }
    }
}

private class StreamHG : StreamHGBase("StreamHG", "hglink.to")
private class Davioad : StreamHGBase("StreamHG (Davioad)", "davioad.com")
private class Haxloppd : StreamHGBase("StreamHG (Haxloppd)", "haxloppd.com")
private class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
private class Cavanhabg : StreamHGBase("StreamHG (Cavanhabg)", "cavanhabg.com")
private class Dumbalag : StreamHGBase("StreamHG (Dumbalag)", "dumbalag.com")

// ===== Forafile: الطريقة 2 - بدون معامل quality =====
private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer)
        val packedJs = document?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                // الطريقة 2: بدون معامل quality في القائمة الرئيسية
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - MP4",
                        url = mp4Link,
                    ) {
                        referer = url
                        isM3u8 = false
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}

// ===== DoodStreamBase: الطريقة 3 - مع headers فقط =====
private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = safeGetAsText(newUrl, referer) ?: return
        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) return

        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text + "z"
        
        // الطريقة 3: مع headers فقط
        callback(
            newExtractorLink(
                source = this.name,
                name = "${this.name} - Video",
                url = trueUrl,
            ) {
                headers = mapOf(
                    "Referer" to newUrl
                )
                isM3u8 = false
                quality = Qualities.Unknown.value
            }
        )
    }
}

private class DoodStream : DoodStreamBase() { 
    override var mainUrl = "doodstream.com" 
}

private class DsvPlay : DoodStreamBase() { 
    override var mainUrl = "dsvplay.com" 
}

// ===== PackedJsExtractorBase: الطريقة 4 - الأبسط =====
private abstract class PackedJsExtractorBase(
    override var name: String,
    override var mainUrl: String,
    private val regex: Regex
) : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer)
        val script = doc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = regex.find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null && videoUrl.isNotBlank()) {
                val finalUrl = if (videoUrl.startsWith("//")) "https:${videoUrl}" else videoUrl
                
                // الطريقة 4: الأبسط - 3 معاملات فقط
                callback(
                    newExtractorLink(
                        this.name,
                        "${this.name} - Video", 
                        finalUrl
                    ) {
                        referer = url
                        isM3u8 = false
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}

private class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())
private class Bigwarp : PackedJsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
private class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())

// ===== Placeholder Extractors =====
private open class PlaceholderExtractor(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(name, "Extractor not yet implemented for $url")
    }
}

private class EarnVids : PlaceholderExtractor("EarnVids", "dingtezuni.com")
private class VidGuard : PlaceholderExtractor("VidGuard", "listeamed.net")
