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
    StreamHGMultiMethod()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Our clean helper function to create links and handle the "deprecated" issue.
@Suppress("DEPRECATION")
private fun createLink(
    source: String,
    name: String,
    url: String,
    referer: String,
    quality: Int,
    type: ExtractorLinkType = ExtractorLinkType.M3U8
): ExtractorLink {
    return ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = type
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

// A single, powerful, multi-strategy extractor class
private class StreamHGMultiMethod : ExtractorApi() {
    // We override these properties in the class itself
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com" // We can change this if the main domain changes
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Since this extractor handles multiple potential domains, let's get the host from the URL
        val currentHost = java.net.URI(url).host ?: mainUrl

        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            // --- ATTEMPT 4 (Fallback): Direct Page Scan ---
            Log.d(name, "No packed JS found. Trying Attempt 4: Direct Page Scan...")
            val directLink = doc.html().let { Regex("""(https?://[^'"]+\.m3u8[^'"]*)""").find(it)?.value }
            if (directLink != null) {
                Log.d(name, "✅ SUCCESS with Direct Page Scan. Link: $directLink")
                callback(createLink(this.name, "${this.name} - Direct", directLink, url, Qualities.Unknown.value))
            } else {
                Log.e(name, "No packed JS and no direct .m3u8 link found on the page.")
            }
            return
        }
        Log.d(name, "Found packed JS. Starting multi-layered extraction...")
        
        var unpackedScript: String? = null

        // --- ATTEMPT 1: Classic Unpacker (getAndUnpack) ---
        try {
            Log.d(name, "Attempt 1: Classic Unpacker (getAndUnpack)...")
            unpackedScript = getAndUnpack(packedJs)
            Log.d(name, "Classic Unpacker succeeded.")
            // --- ATTEMPT 1.1: Look for "hls4" specifically ---
            val hls4Link = Regex("""["']hls4["']\s*:\s*["']([^"']+\.m3u8[^"']*)""").find(unpackedScript)?.groupValues?.get(1)
            if (hls4Link != null) {
                val finalUrl = "https://$currentHost$hls4Link".takeIf { hls4Link.startsWith('/') } ?: hls4Link
                Log.d(name, "✅ SUCCESS with Classic Unpacker (hls4). Link: $finalUrl")
                callback(createLink(this.name, "${this.name} - HLS4", finalUrl, url, Qualities.Unknown.value))
                return
            }
        } catch (e: Exception) {
            Log.e(name, "Attempt 1 FAILED: Classic Unpacker crashed. Error: ${e.message}")
        }

        // --- ATTEMPT 2: Smart Dictionary Regex (on packed script) ---
        try {
            Log.d(name, "Attempt 2: Smart Dictionary Regex...")
            val dictionaryRegex = Regex("'((?:[^']|\\\\'){100,})'\\.split\\('\\|'\\)")
            val dictionaryMatch = dictionaryRegex.find(packedJs)
            if (dictionaryMatch != null) {
                val dictionary = dictionaryMatch.groupValues[1]
                Log.d(name, "Successfully extracted dictionary (length: ${dictionary.length}).")
                val partsRegex = Regex("""stream\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|master\|m3u8""")
                val partsMatch = partsRegex.find(dictionary)
                if (partsMatch != null) {
                    val (p1, p2, p3, p4) = partsMatch.destructured
                    val reconstructedPath = "/stream/$p1/$p2/$p3/$p4/master.m3u8"
                    val finalUrl = "https://$currentHost$reconstructedPath"
                    Log.d(name, "✅ SUCCESS with Smart Dictionary Regex. Link: $finalUrl")
                    callback(createLink(this.name, "${this.name} - Regex", finalUrl, url, Qualities.Unknown.value))
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Attempt 2 FAILED: Smart Dictionary Regex crashed. Error: ${e.message}")
        }
        
        // --- ATTEMPT 3: Generic Player Patterns (on unpacked script, if available) ---
        if (unpackedScript != null) {
            try {
                Log.d(name, "Attempt 3: Generic Player Patterns...")
                // Pattern 1: sources:[{file:"..."}]
                // Pattern 2: file:"..."
                val genericLink = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)""").find(unpackedScript)?.groupValues?.get(1)
                    ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)""").find(unpackedScript)?.groupValues?.get(1)
                
                if (genericLink != null) {
                    val finalUrl = "https://$currentHost$genericLink".takeIf { genericLink.startsWith('/') } ?: genericLink
                    Log.d(name, "✅ SUCCESS with Generic Player Patterns. Link: $finalUrl")
                    callback(createLink(this.name, "${this.name} - Generic", finalUrl, url, Qualities.Unknown.value))
                    return
                }
                Log.w(name, "Generic Player Patterns ran but found no matching link.")
            } catch (e: Exception) {
                 Log.e(name, "Attempt 3 FAILED: Generic Player Patterns crashed. Error: ${e.message}")
            }
        }

        Log.e(name, "❌ ALL EXTRACTION METHODS FAILED for $url")
    }
}
