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

// ... (بقية الاستيرادات والإعدادات تبقى كما هي)

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
                    
                    // المحاولة 1: التوقيع المحتمل
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - HLS",
                            url = fullUrl,
                            quality = Qualities.Unknown.value
                        ) {
                            referer = finalPageUrl
                            isM3u8 = true
                            headers = mapOf("Referer" to finalPageUrl)
                        }
                    )
                    return
                }
            }
        }
    }
}

// ... (بقية الكلاسات المعدلة بنفس الطريقة)

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
                        source = this.name,
                        name = "${this.name} - MP4", 
                        url = mp4Link,
                        quality = Qualities.Unknown.value
                    ) {
                        referer = url
                        isM3u8 = false
                    }
                )
            }
        }
    }
}

// ... (استمر بنفس النمط لبقية الكلاسات)
