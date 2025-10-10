package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now contains our final, self-sufficient extractor.
val extractorList = listOf(
    StreamHGMasterExtractor()
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

// The final extractor, containing its own "Master Key" unpacking logic.
class StreamHGMasterExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    /**
     * This is our "Master Key". A custom-built function to bypass the site's tricks.
     * It finds the secret dictionary and extracts the hls2 link from it.
     */
    private fun extractWithMasterKey(packedJs: String): String? {
        // Stage 1: The Regex to find the secret dictionary.
        // This is a robust regex that looks for the function structure and extracts the longest string,
        // which is always the dictionary. It's resilient to small changes.
        val dictRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\((.*?),['"]((?:\\.|[^"'\\])*)['"]\.split\('\|'\)\)\)""")
        val match = dictRegex.find(packedJs)

        if (match == null || match.groupValues.size < 3) {
            Log.e(name, "Master Key Stage 1 FAILED: Could not find the structure of the packed function.")
            return null
        }
        
        // The dictionary is the second captured group (the long string of words).
        val dictionary = match.groupValues[2]
        Log.d(name, "Master Key Stage 1 SUCCESS: Dictionary extracted.")

        // Stage 2: Search within the dictionary for the real prize (the hls2 link).
        val hls2Regex = Regex("""(https://[a-zA-Z0-9.-]+\.com/[^|]*?master\.m3u8[^|]*)""")
        val hls2Match = hls2Regex.find(dictionary)

        return if (hls2Match != null) {
            val hls2Link = hls2Match.groupValues[1]
            Log.d(name, "Master Key Stage 2 SUCCESS: Found hls2 link: $hls2Link")
            hls2Link
        } else {
            Log.e(name, "Master Key Stage 2 FAILED: Found dictionary, but no hls2 link inside it.")
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
        Log.d(name, "Found packed JS. Executing the Master Key...")
        
        // Call our custom-built function to get the link.
        val finalUrl = extractWithMasterKey(packedJs)
        
        if (finalUrl != null) {
            Log.d(name, "✅ EXTRACTION SUCCESSFUL! Final link: $finalUrl")
            callback(
                createLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl,
                    referer = url,
                    quality = Qualities.Unknown.value
                )
            )
        } else {
            Log.e(name, "❌ FAILED: The Master Key could not find the link.")
        }
    }
}
