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
    StreamHG(),
    Kravaxxa() // We can add other StreamHG mirrors here if needed
)

// BROWSER_HEADERS and CloudflareKiller remain the same.
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

// safeGetAsDocument remains the same.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("SafeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// This is the new, robust, multi-layered extractor.
private abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found on the page.")
            return
        }
        Log.d(name, "Found packed JS. Starting multi-layered extraction...")

        // --- ATTEMPT 1: Classic Unpacker (getAndUnpack) ---
        try {
            Log.d(name, "Attempt 1: Classic Unpacker (getAndUnpack)...")
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""(https?://[^'"]+\.m3u8[^'"]*)""").find(unpacked)?.value
            if (m3u8Link != null) {
                Log.d(name, "✅ SUCCESS with Classic Unpacker. Link: $m3u8Link")
                callback(createLink(this.name, "${this.name} - Unpacked", m3u8Link, url, Qualities.Unknown.value))
                return
            }
            Log.w(name, "Classic Unpacker ran but found no .m3u8 link.")
        } catch (e: Exception) {
            Log.e(name, "Attempt 1 FAILED: Classic Unpacker crashed. Error: ${e.message}")
        }

        // --- ATTEMPT 2: Smart Dictionary Regex ---
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
                    val finalUrl = "https://$mainUrl$reconstructedPath"
                    Log.d(name, "✅ SUCCESS with Smart Dictionary Regex. Link: $finalUrl")
                    callback(createLink(this.name, "${this.name} - Regex", finalUrl, url, Qualities.Unknown.value))
                    return
                }
                Log.w(name, "Smart Dictionary Regex extracted dictionary but couldn't find link parts.")
            } else {
                Log.w(name, "Smart Dictionary Regex couldn't find the dictionary.")
            }
        } catch (e: Exception) {
            Log.e(name, "Attempt 2 FAILED: Smart Dictionary Regex crashed. Error: ${e.message}")
        }

        // --- ATTEMPT 3: QuickJS Engine Execution ---
        try {
            Log.d(name, "Attempt 3: Executing with QuickJS Engine...")
            // We ask QuickJS to run the script and then give us the value of "links.hls4"
            val jsToRun = "$packedJs\nlinks.hls4;"
            val result = app.quickJs.evaluate(jsToRun) as? String
            if (result != null && result.contains(".m3u8")) {
                val finalUrl = when {
                    result.startsWith("//") -> "https:$result"
                    result.startsWith("/") -> "https://$mainUrl$result"
                    else -> result
                }
                Log.d(name, "✅ SUCCESS with QuickJS Engine. Link: $finalUrl")
                callback(createLink(this.name, "${this.name} - QuickJS", finalUrl, url, Qualities.Unknown.value))
                return
            }
            Log.w(name, "QuickJS Engine executed but result was not a valid link. Result: $result")
        } catch (e: Exception) {
            Log.e(name, "Attempt 3 FAILED: QuickJS Engine crashed. Error: ${e.message}")
        }

        Log.e(name, "❌ ALL EXTRACTION METHODS FAILED for $url")
    }
}

// Define the specific servers using the new robust base class.
private class StreamHG : StreamHGBase("StreamHG", "hglink.to")
private class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
