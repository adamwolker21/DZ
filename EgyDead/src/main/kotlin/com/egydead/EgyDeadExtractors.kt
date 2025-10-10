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

// The extractor list is now clean and focused.
val extractorList = listOf(
    StreamHGExtractor()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// This is our final helper function. It's the only way to pass the referer
// while also being able to build the project, by suppressing the deprecation error.
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

// The final, most robust extractor compatible with your project environment.
class StreamHGExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val currentHost = java.net.URI(url).host ?: mainUrl
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Starting extraction...")
        
        var m3u8Link: String? = null

        // --- ATTEMPT 1: Classic Unpacker ---
        try {
            val unpacked = getAndUnpack(packedJs)
            val hls4Link = Regex("""["']hls4["']\s*:\s*["']([^"']+\.m3u8[^"']*)""").find(unpacked)?.groupValues?.get(1)
            if (hls4Link != null) {
                m3u8Link = "https://$currentHost$hls4Link".takeIf { hls4Link.startsWith('/') } ?: hls4Link
                Log.d(name, "Success with Classic Unpacker.")
            }
        } catch (e: Exception) {
            Log.w(name, "Classic Unpacker failed. Trying next method.")
        }

        // --- ATTEMPT 2: Smart Dictionary Regex (if previous failed) ---
        if (m3u8Link == null) {
            try {
                val dictionaryRegex = Regex("'((?:[^']|\\\\'){100,})'\\.split\\('\\|'\\)")
                val dictionaryMatch = dictionaryRegex.find(packedJs)
                if (dictionaryMatch != null) {
                    val dictionary = dictionaryMatch.groupValues[1]
                    val partsRegex = Regex("""stream\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|master\|m3u8""")
                    val partsMatch = partsRegex.find(dictionary)
                    if (partsMatch != null) {
                        val (p1, p2, p3, p4) = partsMatch.destructured
                        m3u8Link = "https://$currentHost/stream/$p1/$p2/$p3/$p4/master.m3u8"
                        Log.d(name, "Success with Smart Dictionary Regex.")
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "Smart Dictionary Regex failed.")
            }
        }
        
        // If we found a link with any method, create the link using our special helper.
        if (m3u8Link != null) {
            Log.d(name, "✅ Link found: $m3u8Link. Creating final link.")
            callback(
                createLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Link,
                    referer = url, // Pass the crucial referer
                    quality = Qualities.Unknown.value
                )
            )
        } else {
            Log.e(name, "❌ ALL EXTRACTION METHODS FAILED for $url")
        }
    }
}
