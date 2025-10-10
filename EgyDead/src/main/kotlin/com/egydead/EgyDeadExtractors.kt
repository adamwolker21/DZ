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

// The final extractor, containing its own "Engine" to build the correct link.
class StreamHGEngineeredExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "kravaxxa.com"
    override val requiresReferer = true

    /**
     * This is our "Engine". It deconstructs the packed script, finds the necessary
     * components (server, token, etc.), and builds the correct hls2 link.
     */
    private fun buildLinkWithEngine(packedJs: String, videoId: String): String? {
        // Stage 1: The Master Key to extract the dictionary.
        val dictRegex = Regex("""['"]((?:\\.|[^"'\\]){200,})['"]\.split\(['']\|['']\)""")
        val dictionaryMatch = dictRegex.find(packedJs)
        if (dictionaryMatch == null) {
            Log.e(name, "Engine FAILED: Could not extract the dictionary.")
            return null
        }
        val dictionary = dictionaryMatch.groupValues[1]
        Log.d(name, "Engine Stage 1 SUCCESS: Dictionary extracted.")

        // Stage 2: Gather the components from the dictionary.
        val server = Regex("""([a-z0-9]+?\.premilkyway\.com)""").find(dictionary)?.groupValues?.get(1)
        val staticId = Regex("""\b(\d{5})\b""").find(dictionary)?.groupValues?.get(1) // Looks for a 5-digit number
        val token = Regex("""(\?t=[^|]+)""").find(dictionary)?.groupValues?.get(1)

        if (server == null || staticId == null || token == null) {
            Log.e(name, "Engine FAILED: Could not find all required components in the dictionary.")
            Log.d(name, "Server: $server, StaticID: $staticId, Token: $token")
            return null
        }
        Log.d(name, "Engine Stage 2 SUCCESS: All components found.")
        Log.d(name, "--> Server: $server")
        Log.d(name, "--> Static ID: $staticId")
        Log.d(name, "--> Token: $token")
        Log.d(name, "--> Video ID: $videoId")


        // Stage 3: Build the final, correct URL using the components.
        val finalUrl = "https://$server/hls2/01/$staticId/${videoId}_,l,n,h,.urlset/master.m3u8$token"
        Log.d(name, "Engine Stage 3 SUCCESS: Final URL built.")
        
        return finalUrl
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // The videoId is the key to building the correct link.
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(name, "Could not extract videoId from URL: $url")
            return
        }

        val doc = safeGetAsDocument(url, referer) ?: return
        Log.d(name, "Page loaded successfully from $url")

        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null) {
            Log.e(name, "No packed 'eval' JavaScript found.")
            return
        }
        Log.d(name, "Found packed JS. Executing the Engine...")
        
        // Call our custom-built engine to get the link.
        val finalUrl = buildLinkWithEngine(packedJs, videoId)
        
        if (finalUrl != null) {
            Log.d(name, "✅ EXTRACTION & BUILD SUCCESSFUL! Final link: $finalUrl")
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
            Log.e(name, "❌ FAILED: The Engine could not build the link.")
        }
    }
}
