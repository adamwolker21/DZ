package com.krmzi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// مستخرج سيرفر Arab HD
open class ArabHdExtractor(
    override var name: String = "Arab HD",
    override var mainUrl: String = "arabhd.onl"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, referer = "https://$mainUrl")
            val doc = response.document
            
            // البحث عن السكريبت المشفر بصيغة eval
            val script = doc.select("script").find { it.data().contains("eval(function") }?.data() ?: ""
            
            // فك التشفير باستخدام الأداة الحديثة JsUnpacker
            val unpacked = if (script.isNotEmpty()) {
                JsUnpacker(script).unpack() ?: response.text
            } else {
                response.text
            }

            // استخراج رابط الـ m3u8
            val m3u8Regex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val match = m3u8Regex.find(unpacked)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                
                // إنشاء الرابط وإرجاعه للتطبيق بالنظام الجديد
                extractedLinks.add(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://$mainUrl",
                            "Referer" to "https://$mainUrl/"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return if (extractedLinks.isNotEmpty()) extractedLinks else null
    }
}

// مستخرج سيرفر Red HD
class RedHdExtractor : ArabHdExtractor("Red HD", "redhd.onl")
