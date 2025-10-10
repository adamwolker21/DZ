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

// The extractor list now contains our experimental external API extractor.
val extractorList = listOf(
    StreamHGExternalExtractor()
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

// Experimental extractor that uses an external service (de4js.kshift.me) to deobfuscate.
class StreamHGExternalExtractor : ExtractorApi() {
    override var name = "StreamHG (External)"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Sending to external deobfuscator (de4js.kshift.me)...")

        try {
            // Step 1: Send the packed script to the external deobfuscation service.
            val responseDoc = app.post(
                "https://de4js.kshift.me/",
                data = mapOf("input_code" to packedJs, "autodecode" to "1"),
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "CloudStream-Extractor-Client" // Identify our client
                )
            ).document
            Log.d(name, "Received response from external deobfuscator.")

            // Step 2: Extract the deobfuscated code from the result page.
            val deobfuscatedResult = responseDoc.selectFirst("textarea#result_code")?.text()

            if (deobfuscatedResult.isNullOrBlank()) {
                Log.e(name, "External deobfuscator did not return a valid result.")
                return
            }
            Log.d(name, "Successfully extracted deobfuscated code. Searching for hls2 link...")

            // Step 3: Search for the hls2 link within the deobfuscated code.
            val hls2Regex = Regex(""""hls2"\s*:\s*"([^"]+)"""")
            val match = hls2Regex.find(deobfuscatedResult)

            if (match != null) {
                val finalUrl = match.groupValues[1]
                Log.d(name, "✅ SUCCESS! Found the direct hls2 link via external service: $finalUrl")
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
                Log.e(name, "❌ Extraction failed: Could not find the 'hls2' link in the deobfuscated result.")
            }
        } catch (e: Exception) {
            Log.e(name, "❌ An error occurred during the external deobfuscation process: ${e.message}")
        }
    }
                                  }
