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

val extractorList = listOf(StreamHG())

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

class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true

    private val potentialHosts = listOf("kravaxxa.com")

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

            val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
            if (packedJs.isNullOrBlank()) continue

            try {
                val unpacked = getAndUnpack(packedJs)
                val m3u8Link = Regex("""['"]hls2['"]\s*:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1)

                if (m3u8Link != null) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Link,
                            referer = finalPageUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return
                }
            } catch (e: Exception) {
                // Continue to next host
            }
        }
    }
}
