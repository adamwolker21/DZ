package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
// We no longer need getAndUnpack, as we are building our own.
// import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now contains our final, self-sufficient extractor.
val extractorList = listOf(
    StreamHGCustomExtractor()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// This helper function is our final solution to the build issues.
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

// The final extractor, containing its own custom-built unpacking logic.
class StreamHGCustomExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    /**
     * This is our custom-built "key". It bypasses the faulty getAndUnpack function.
     * It extracts the secret dictionary from the packed JS and finds the hls2 link inside it.
     */
    private fun customUnpackAndFindLink(packedJs: String): String? {
        // Step 1: A powerful and flexible Regex to find the dictionary.
        // It handles both single (') and double (") quotes.
        val dictionaryRegex = Regex("""['"]((?:\\.|[^"'\\]){100,})['"]\.split\(['']\|['']\)""")
        val dictionaryMatch = dictionaryRegex.find(packedJs)

        if (dictionaryMatch == null) {
            Log.e(name, "Custom Unpacker: Could not find the secret dictionary.")
            return null
        }
        
        // The dictionary is the long string of words.
        val dictionary = dictionaryMatch.groupValues[1]
        Log.d(name, "Custom Unpacker: Successfully extracted the dictionary.")

        // Step 2: Search within the dictionary for the direct hls2 link.
        // We look for a full, direct HTTPS link ending in .m3u8.
        val hls2Regex = Regex("""(https://[a-zA-Z0-9.-]+\.com/[^|]*?master\.m3u8[^|]*)""")
        val hls2Match = hls2Regex.find(dictionary)

        return if (hls2Match != null) {
            val hls2Link = hls2Match.groupValues[1]
            Log.d(name, "Custom Unpacker: Found hls2 link in dictionary: $hls2Link")
            hls2Link
        } else {
            Log.e(name, "Custom Unpacker: Found dictionary, but no hls2 link inside it.")
            null
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Executing our custom unpacker...")
        
        // Call our custom-built function to get the link.
        val finalUrl = customUnpackAndFindLink(packedJs)
        
        if (finalUrl != null) {
            Log.d(name, "✅ SUCCESS! Final link extracted: $finalUrl")
            callback(
                createLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl,
                    referer = url, // Pass the crucial referer
                    quality = Qualities.Unknown.value
                )
            )
        } else {
            Log.e(name, "❌ FAILED: Our custom unpacker could not find the link.")
        }
    }
}
