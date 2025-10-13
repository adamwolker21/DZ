package com.egydead

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.jsoup.nodes.Document
import android.util.Log
import org.json.JSONObject

val extractorList = listOf(
    StreamHG(),
    Vidshare(),
    Earnvids()
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// =========================================================================
//  ✅ StreamHG (تم تحديثه للنمط الحديث والمستقر)
// =========================================================================
open class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "hglink.to"
    override val requiresReferer = true
    private val host = "https://kravaxxa.com"
    private val logTag = "StreamHG-Extractor"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.d(logTag, "Extractor started for URL: $url")
        try {
            val videoId = url.substringAfterLast("/")
            if (videoId.isBlank()) {
                Log.e(logTag, "Failed to extract video ID.")
                return null
            }

            val finalPageUrl = "$host/e/$videoId"
            Log.d(logTag, "Attempting to GET page: $finalPageUrl")

            val doc = app.get(finalPageUrl, referer = referer, interceptor = cloudflareKiller).document
            val packedJs = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()

            if (packedJs == null) {
                Log.e(logTag, "Could not find packed JS on the page.")
                return null
            }
            Log.d(logTag, "Packed JS found. Unpacking...")

            val unpacked = getAndUnpack(packedJs)
            Log.d(logTag, "Successfully unpacked JS.")

            // نمط البحث قد يختلف، ولكن هذا هو الشائع
            val m3u8Link = unpacked.substringAfter("""hls2":"_URL_""").substringBefore("""_URL_"""")

            if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                Log.d(logTag, "Found m3u8 link: $m3u8Link")
                return listOf(
                    newExtractorLink(source = this.name, name = this.name, url = m3u8Link) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.Unknown.value // Corrected type
                    }
                )
            } else {
                Log.e(logTag, "Failed to extract a valid m3u8 link from unpacked JS.")
                return null
            }
        } catch (e: Exception) {
            Log.e(logTag, "An unexpected error occurred: ${e.message}", e)
            return null
        }
    }
}


// =========================================================================
//  Packed Extractors (Vidshare, Earnvids) - لا تغيير هنا
// =========================================================================
open class PackedExtractorBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    private val logTag = "PackedExtractor"

    private fun findUrlInUnpackedJs(unpackedJs: String): String? {
        Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
        Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
        Regex("""file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
        return null
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val playerPageContent = app.get(url, referer = referer, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) return null

            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null
            
            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
            
            return listOf(
                newExtractorLink(source = this.name, name = this.name, url = finalUrlWithHeaders) {
                    this.referer = url
                }
            )
        } catch (e: Exception) {
            Log.e(logTag, "[$name] An unexpected error occurred: ${e.message}", e)
            return null
        }
    }
}

class Vidshare : PackedExtractorBase("Vidshare", "1vid1shar.com")
class Earnvids : PackedExtractorBase("Earnvids", "dingtezuni.com")
