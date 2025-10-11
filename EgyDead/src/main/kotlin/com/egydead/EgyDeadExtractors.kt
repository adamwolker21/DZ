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

// The final extractor list, containing the user-designed solution with a robust regex.
val extractorList = listOf(
    StreamHGFinalEngine()
)

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar=q=0.8",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Helper function to create links and bypass build restrictions.
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

// The final extractor, implementing the user's brilliant deobfuscation algorithm.
class StreamHGFinalEngine : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    /**
     * A direct Kotlin translation of the user's successful deobfuscateCode function.
     */
    private fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
        var template = p
        for (i in (c - 1) downTo 0) {
            if (i < k.size && k[i].isNotBlank()) {
                val placeholder = i.toString(a)
                template = template.replace(Regex("\\b$placeholder\\b"), k[i])
            }
        }
        return template
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Executing the user-designed Final Engine...")

        // Stage 1: The NEW, more FLEXIBLE deconstruction regex.
        // It focuses on the core pattern and is resilient to minor structural changes.
        val masterRegex = Regex("""\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)""")
        val match = masterRegex.find(packedJs)

        if (match == null || match.groupValues.size < 5) {
            Log.e(name, "Engine FAILED: Could not deconstruct the packed function structure with the flexible regex.")
            return
        }
        Log.d(name, "Engine Stage 1 SUCCESS: Deconstructed packed script into its core components.")

        val (packedString, baseStr, countStr, keyString) = match.destructured
        val base = baseStr.toIntOrNull() ?: 36
        val count = countStr.toIntOrNull() ?: 0
        val keys = keyString.split("|")

        // Stage 2: Re-construction using our deobfuscate function.
        val deobfuscatedJs = deobfuscate(packedString, base, count, keys)
        Log.d(name, "Engine Stage 2 SUCCESS: Deobfuscation complete.")

        // Stage 3: Extraction of the final prize.
        val hls2Regex = Regex(""""hls2"\s*:\s*"([^"]+)"""")
        val urlMatch = hls2Regex.find(deobfuscatedJs)

        if (urlMatch != null) {
            val finalUrl = urlMatch.groupValues[1]
            Log.d(name, "Engine Stage 3 SUCCESS: Found hls2 link: $finalUrl")
            Log.d(name, "✅ EXTRACTION & DEOBFUSCATION SUCCESSFUL!")

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
            Log.e(name, "Engine Stage 3 FAILED: Could not find 'hls2' link in the deobfuscated script.")
        }
    }
                              }
