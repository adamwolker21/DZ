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

private abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // تم تقليص القائمة للتركيز على المضيف المحدد
    private val potentialHosts = listOf(
        "kravaxxa.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(name, "Video ID is blank for URL: $url")
            return
        }

        Log.d(name, "Starting extraction for video ID: $videoId")

        // التكرار على كل مضيف محتمل للعثور على رابط صالح
        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d(name, "Trying host: $host with URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url)
            if (doc == null) {
                Log.e(name, "Failed to get document from: $finalPageUrl")
                continue // انتقل إلى المضيف التالي إذا فشل جلب الصفحة
            }

            Log.d(name, "Successfully retrieved document from: $host")

            // --- الطريقة الأساسية: البحث عن الجافاسكريبت المعبأ ---
            val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs != null) {
                Log.d(name, "Found packed JavaScript on: $host, length: ${packedJs.length}")
                try {
                    val unpacked = getAndUnpack(packedJs)
                    val m3u8Regex = Regex("""file.*?:\s*"([^"]+\.m3u8[^"]*)"""")
                    val match = m3u8Regex.find(unpacked)
                    if (match != null) {
                        val foundUrl = match.groupValues[1]
                        Log.d(name, "✅ SUCCESS (Packed): Found m3u8 link: $foundUrl")
                        callback(
                            newExtractorLink(this.name, "${this.name} - HLS", foundUrl)
                        )
                        return // تم العثور على الرابط، قم بإنهاء الدالة
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error during unpacking or regex on packed JS: ${e.message}")
                }
            }

            // --- الطريقة الاحتياطية: البحث في جميع السكريبتات عن رابط مباشر ---
            Log.d(name, "Packed JS not found or failed. Starting fallback search on $host.")
            val allScripts = doc.select("script")
            for (script in allScripts) {
                val scriptContent = script.data()
                // Regex للبحث عن أي رابط ينتهي بـ .m3u8 داخل علامات الاقتباس
                val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
                val match = m3u8Regex.find(scriptContent)

                if (match != null) {
                    val extractedUrl = match.groupValues[1]
                    Log.d(name, "Fallback found potential m3u8 link: $extractedUrl")

                    // بناء الرابط الكامل
                    val finalUrl = when {
                        extractedUrl.startsWith("//") -> "https:$extractedUrl"
                        extractedUrl.startsWith("/") -> "https://$host$extractedUrl"
                        extractedUrl.startsWith("http") -> extractedUrl
                        else -> null
                    }

                    if (finalUrl != null) {
                        Log.d(name, "✅ SUCCESS (Fallback): Final m3u8 link: $finalUrl")
                        callback(
                            newExtractorLink(
                                this.name,
                                "${this.name} - HLS (Fallback)",
                                finalUrl
                            )
                        )
                        return // تم العثور على الرابط، قم بإنهاء الدالة
                    }
                }
            }
            Log.e(name, "No m3u8 link found on $host with either method.")
        }

        Log.e(name, "❌ FAILED: No working hosts found for video: $videoId")
    }
}


private class StreamHG : StreamHGBase("StreamHG", "hglink.to")
private class Davioad : StreamHGBase("StreamHG (Davioad)", "davioad.com")
private class Haxloppd : StreamHGBase("StreamHG (Haxloppd)", "haxloppd.com")
private class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
private class Cavanhabg : StreamHGBase("StreamHG (Cavanhabg)", "cavanhabg.com")
private class Dumbalag : StreamHGBase("StreamHG (Dumbalag)", "dumbalag.com")

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
                callback(
                    newExtractorLink(
                        this.name,
                        "${this.name} - MP4",
                        mp4Link
                    )
                )
            }
        }
    }
}

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
        
        callback(
            newExtractorLink(
                this.name,
                "${this.name} - Video",
                trueUrl
            )
        )
    }
}

private class DoodStream : DoodStreamBase() { 
    override var mainUrl = "doodstream.com" 
}

private class DsvPlay : DoodStreamBase() { 
    override var mainUrl = "dsvplay.com" 
}

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
                
                callback(
                    newExtractorLink(
                        this.name,
                        "${this.name} - Video", 
                        finalUrl
                    )
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

private open class PlaceholderExtractor(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(name, "Extractor not yet implemented for $url")
    }
}

private class EarnVids : PlaceholderExtractor("EarnVids", "dingtezuni.com")
private class VidGuard : PlaceholderExtractor("VidGuard", "listeamed.net")
