package com.krmzi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.Unpacker

// مستخرج سيرفر Arab HD
open class ArabHdExtractor(
    override val name: String = "Arab HD",
    override val mainUrl: String = "https://arabhd.onl"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = mainUrl)
            val doc = response.document
            
            // البحث عن السكريبت المشفر بصيغة eval
            val script = doc.select("script").find { it.data().contains("eval(function") }?.data() ?: ""
            
            // استخدام أداة فك التشفير الخاصة بـ Cloudstream
            val unpacked = if (script.isNotEmpty()) {
                Unpacker.unpack(script)
            } else {
                response.text
            }

            // استخراج رابط الـ m3u8 باستخدام Regex (التعبيرات النمطية)
            val m3u8Regex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val match = m3u8Regex.find(unpacked)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                
                // إرسال الرابط للتطبيق مع الـ Headers الضرورية لتخطي الحماية
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// مستخرج سيرفر Red HD (يعمل بنفس آلية Arab HD تقريباً ولكن بنطاق مختلف)
class RedHdExtractor : ArabHdExtractor("Red HD", "https://redhd.onl")
