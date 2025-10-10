package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// قائمة المستخرجات الآن نظيفة وتحتوي فقط على الحل النهائي
val extractorList = listOf(
    StreamHGFinalExtractor()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// الدالة المساعدة النهائية التي تتجاوز قيود البناء
@Suppress("DEPRECATION")
private fun createLink(
    source: String,
    name: String,
    url: String,
    referer: String,
    quality: Int
): ExtractorLink {
    return ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = ExtractorLinkType.M3U8
    )
}

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("SafeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// الحل النهائي: مستخرج واحد، طريقة واحدة مضمونة، بناءً على اكتشافك
class StreamHGFinalExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Attempting final extraction based on hls2 discovery...")
        
        try {
            // الخطوة 1: فك تشفير السكريبت باستخدام الطريقة المضمونة
            val unpacked = getAndUnpack(packedJs)
            Log.d(name, "Successfully unpacked the script.")

            // الخطوة 2: استهداف "الجائزة الحقيقية" (hls2) باستخدام تعبير نمطي بسيط ومباشر
            val hls2Regex = Regex(""""hls2"\s*:\s*"([^"]+)"""")
            val match = hls2Regex.find(unpacked)

            if (match != null) {
                // الرابط المستهدف هو الرابط الكامل والمباشر، لا يحتاج إلى بناء
                val finalUrl = match.groupValues[1]
                Log.d(name, "✅ SUCCESS! Found the direct hls2 link: $finalUrl")

                // الخطوة 3: إنشاء الرابط النهائي باستخدام الدالة المساعدة التي تحل مشاكل البناء
                callback(
                    createLink(
                        source = this.name,
                        name = this.name, // يمكننا إضافة الجودة هنا لاحقًا إذا أردنا
                        url = finalUrl,
                        referer = url, // نمرر الـ referer كإجراء احترازي
                        quality = Qualities.Unknown.value
                    )
                )
            } else {
                Log.e(name, "❌ Extraction failed: Could not find the 'hls2' link in the unpacked script.")
            }
        } catch (e: Exception) {
            Log.e(name, "❌ An error occurred during the unpacking process: ${e.message}")
        }
    }
                                  }
