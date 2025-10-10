package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now points to our final, robust extractor.
val extractorList = listOf(
    StreamHGResolver()
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

// Data class to hold our secret message for the resolver
private data class ResolverData(val url: String, val referer: String)

// This is the final, architecturally correct extractor using the built-in resolver system.
class StreamHGResolver : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    // This function will be called by the app when it sees our "resolver://" link.
    // It's the designated place to add the final headers and referer.
    @Suppress("DEPRECATION")
    override suspend fun load(url: String): ExtractorLink? {
        Log.d(name, "LOAD FUNCTION TRIGGERED")
        // The url is our secret message: "resolver://BASE64_ENCODED_DATA"
        val decodedData = base64Decode(url.removePrefix("resolver://"))
        Log.d(name, "Decoded resolver data: $decodedData")
        
        // Split the secret message to get the real URL and referer
        val (realUrl, referer) = decodedData.split("|||")
        Log.d(name, "Real URL: $realUrl")
        Log.d(name, "Referer: $referer")

        // Now, we create the final ExtractorLink with all the necessary info.
        // This is the correct and safe place to do this.
        return ExtractorLink(
            source = this.name,
            name = this.name,
            url = realUrl,
            referer = referer,
            quality = Qualities.Unknown.value,
            isM3u8 = true,
            headers = BROWSER_HEADERS
        )
    }

    // This function's only job is to find the m3u8 link and create a "secret message" (resolver link) for the load() function.
    override suspend fun getUrl(
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
        Log.d(name, "Found packed JS. Starting extraction to create resolver link...")
        
        var m3u8Link: String? = null

        // --- ATTEMPT 1: Classic Unpacker ---
        try {
            val unpacked = getAndUnpack(packedJs)
            val hls4Link = Regex("""["']hls4["']\s*:\s*["']([^"']+\.m3u8[^"']*)""").find(unpacked)?.groupValues?.get(1)
            if (hls4Link != null) {
                m3u8Link = "https://$currentHost$hls4Link".takeIf { hls4Link.startsWith('/') } ?: hls4Link
                Log.d(name, "Success with Classic Unpacker. Link found: $m3u8Link")
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
                        Log.d(name, "Success with Smart Dictionary Regex. Link found: $m3u8Link")
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "Smart Dictionary Regex failed. Error: ${e.message}")
            }
        }
        
        // If we found a link, create and send the resolver link
        if (m3u8Link != null) {
            // Create the secret message: "REAL_URL|||REFERER_URL"
            val resolverData = "$m3u8Link|||$url"
            // Encode it in Base64 to make it URL-safe
            val encodedData = base64Encode(resolverData)
            Log.d(name, "Encoded resolver data: $encodedData")
            
            // This is the link we will pass to the app. It doesn't cause build errors.
            val resolverUrl = "resolver://$encodedData"
            Log.d(name, "✅ Link found. Creating and sending resolver link: $resolverUrl")
            
            @Suppress("DEPRECATION")
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = resolverUrl, // We send the secret message, not the real URL
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        } else {
            Log.e(name, "❌ ALL EXTRACTION METHODS FAILED for $url")
        }
    }
}
