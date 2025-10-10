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

// The extractor list now contains our final, engineered solution.
val extractorList = listOf(
    StreamHGEngineeredExtractor()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar=q=0.8",
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

// The final extractor, containing its own "Engine" to build the correct link.
class StreamHGEngineeredExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    /**
     * This is our "Engine". It perfectly simulates the packed script's logic
     * to deobfuscate it and then extracts the true hls2 link.
     */
    private fun deobfuscateAndExtract(packedJs: String): String? {
        // Stage 1: The Master Regex to deconstruct the packed function.
        // It captures the template (p), radix (a), count (c), and dictionary (k).
        val masterRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)\)""")
        val match = masterRegex.find(packedJs)

        if (match == null || match.groupValues.size < 5) {
            Log.e(name, "Engine FAILED: Could not deconstruct the packed function structure.")
            return null
        }

        var template = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: 36
        val count = match.groupValues[3].toIntOrNull() ?: 0
        val dictionary = match.groupValues[4].split("|")

        if (count != dictionary.size) {
            Log.e(name, "Engine FAILED: Dictionary count mismatch.")
            return null
        }
        Log.d(name, "Engine Stage 1 SUCCESS: Deconstructed packed script.")

        // Stage 2: The "Fill-in-the-blanks" machine.
        // This loop perfectly simulates the logic of the original eval function.
        for (i in (count - 1) downTo 0) {
            val keyword = dictionary[i]
            if (keyword.isNotBlank()) {
                val placeholder = i.toString(radix)
                // Using regex for whole-word replacement (\b = word boundary)
                template = template.replace(Regex("\\b$placeholder\\b"), keyword)
            }
        }
        Log.d(name, "Engine Stage 2 SUCCESS: Deobfuscation complete.")
        // Log.d(name, "Deobfuscated Script: $template") // Uncomment for deep debugging

        // Stage 3: Pluck the fruit.
        // Now that the script is clean, we just need to find our prize.
        val hls2Regex = Regex(""""hls2"\s*:\s*"([^"]+)"""")
        val hls2Match = hls2Regex.find(template)

        return if (hls2Match != null) {
            val finalUrl = hls2Match.groupValues[1]
            Log.d(name, "Engine Stage 3 SUCCESS: Found hls2 link: $finalUrl")
            finalUrl
        } else {
            Log.e(name, "Engine Stage 3 FAILED: Could not find hls2 link in the deobfuscated script.")
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
        Log.d(name, "Found packed JS. Executing the Final Engine...")

        // Call our custom-built engine to get the link.
        val finalUrl = deobfuscateAndExtract(packedJs)

        if (finalUrl != null) {
            Log.d(name, "✅ EXTRACTION & DEOBFUSCATION SUCCESSFUL! Final link: $finalUrl")
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
            Log.e(name, "❌ FAILED: The Final Engine could not find the link.")
        }
    }
                              }
