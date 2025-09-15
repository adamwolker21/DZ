package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app
import org.jsoup.nodes.Element

class FaselHDSExtractor {
    suspend fun extractFromUrl(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // إرسال طلب إلى صفحة video_player مع headers مناسبة
            val document = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://www.faselhds.life/",
                "Origin" to "https://www.faselhds.life",
                "Accept" to "*/*",
                "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
                "Accept-Encoding" to "gzip, deflate, br"
            )).document

            // البحث عن عنصر الفيديو مباشرة
            val videoElement = document.selectFirst("video#video")
            val videoSrc = videoElement?.attr("src")

            if (!videoSrc.isNullOrEmpty() && videoSrc.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        "FaselHDS",
                        "FaselHDS - HLS",
                        videoSrc,
                        url,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }

            // البحث عن iframe داخلي قد يحتوي على الفيديو
            val iframeElement = document.selectFirst("iframe")
            val iframeSrc = iframeElement?.attr("src")

            if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        "FaselHDS",
                        "FaselHDS - HLS",
                        iframeSrc,
                        url,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }

            // البحث عن scripts التي قد تحتوي على روابط الفيديو
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                // البحث عن روابط m3u8 في الـ scripts
                val m3u8Regex = Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)""")
                val matches = m3u8Regex.findAll(scriptContent)
                
                for (match in matches) {
                    val m3u8Url = match.value
                    callback.invoke(
                        ExtractorLink(
                            "FaselHDS",
                            "FaselHDS - HLS",
                            m3u8Url,
                            url,
                            Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }
}
