package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import android.util.Log

// سنقوم بتجربة عدة extractors مختلفة
val extractorList = listOf(
    StreamHG1(),
    StreamHG2(),
    StreamHG3(),
    StreamHG4(),
    StreamHG5(),
    StreamHG6(),
    StreamHG7(),
    StreamHG8()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        null
    }
}

// دالة مساعدة لاستخراج الرابط، لتجنب تكرار الكود
private suspend fun extractM3u8Link(url: String, referer: String?): String? {
    val videoId = url.substringAfterLast("/")
    if (videoId.isBlank()) return null

    val potentialHosts = listOf("kravaxxa.com")
    for (host in potentialHosts) {
        val finalPageUrl = "https://$host/e/$videoId"
        val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

        val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
        if (packedJs.isNullOrBlank()) continue

        try {
            val unpacked = getAndUnpack(packedJs)
            return Regex("""['"]hls2['"]\s*:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1)
        } catch (e: Exception) {
            // Continue to next host
        }
    }
    return null
}

// ===== التجارب المختلفة لـ newExtractorLink =====

// التجربة 1: 3 معاملات أساسية
class StreamHG1 : ExtractorApi() {
    override var name = "StreamHG1"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(newExtractorLink(this.name, this.name, m3u8Link))
    }
}

// التجربة 2: 4 معاملات مع referer
class StreamHG2 : ExtractorApi() {
    override var name = "StreamHG2"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(newExtractorLink(this.name, this.name, m3u8Link, referer ?: ""))
    }
}

// التجربة 3: 5 معاملات مع quality
class StreamHG3 : ExtractorApi() {
    override var name = "StreamHG3"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(newExtractorLink(this.name, this.name, m3u8Link, referer ?: "", Qualities.Unknown.value))
    }
}

// التجربة 4: 6 معاملات مع isM3u8
class StreamHG4 : ExtractorApi() {
    override var name = "StreamHG4"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(newExtractorLink(this.name, this.name, m3u8Link, referer ?: "", Qualities.Unknown.value, true))
    }
}

// التجربة 5: named parameters (الأساسية)
class StreamHG5 : ExtractorApi() {
    override var name = "StreamHG5"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Link
            )
        )
    }
}

// التجربة 6: named parameters مع referer
class StreamHG6 : ExtractorApi() {
    override var name = "StreamHG6"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Link,
                referer = referer ?: ""
            )
        )
    }
}

// التجربة 7: named parameters مع referer و quality
class StreamHG7 : ExtractorApi() {
    override var name = "StreamHG7"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Link,
                referer = referer ?: "",
                quality = Qualities.Unknown.value
            )
        )
    }
}

// التجربة 8: named parameters مع referer و quality و isM3u8
class StreamHG8 : ExtractorApi() {
    override var name = "StreamHG8"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3u8Link = extractM3u8Link(url, referer) ?: return
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Link,
                referer = referer ?: "",
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
    }
}
