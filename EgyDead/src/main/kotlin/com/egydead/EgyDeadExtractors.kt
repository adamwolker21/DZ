package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.loadExtractor
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

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("safeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// This is the final, most robust version of the extractor.
class StreamHGExtractor : ExtractorApi() {
    override var name = "StreamHG"
    // We don't have a single mainUrl, as it handles mirrors.
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    // This function is called when the user clicks on a link from this extractor.
    // It will handle the proxying.
    override suspend fun getUrl(url: String, referer: String?): ExtractorLink? {
        // The URL will be the real m3u8 link we stored in `extractorData`
        Log.d(name, "Proxying request for url: $url with referer: $referer")
        return ExtractorLink(
            source = this.name,
            name = this.name,
            url = url,
            referer = referer ?: "",
            quality = Qualities.Unknown.value,
            isM3u8 = true,
            headers = BROWSER_HEADERS // We add all browser headers for maximum compatibility
        )
    }

    // This is the main function that finds the links on the page.
    override suspend fun getUrls(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val currentHost = java.net.URI(url).host ?: mainUrl
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found on the page.")
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
                Log.d(name, "Success with Classic Unpacker. Link: $m3u8Link")
            }
        } catch (e: Exception) {
            Log.w(name, "Classic Unpacker failed. Trying next method. Error: ${e.message}")
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
                        Log.d(name, "Success with Smart Dictionary Regex. Link: $m3u8Link")
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "Smart Dictionary Regex failed. Error: ${e.message}")
            }
        }
        
        // If we found a link with any method, create the special proxy link
        if (m3u8Link != null) {
             Log.d(name, "✅ Link found. Creating proxy link.")
            // We create a special link that points back to this extractor.
            // The real m3u8 URL is stored in the `url` field.
            // The original page URL is stored as the `referer`.
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    // This is the PROXY URL that the app will call
                    url = m3u8Link,
                    referer = url, // This is the page hosting the video
                    quality = Qualities.Unknown.value,
                    // The loadExtractor function will be called when the link is used
                    isM3u8 = true
                )
            )
        } else {
            Log.e(name, "❌ ALL EXTRACTION METHODS FAILED for $url")
        }
    }
}
