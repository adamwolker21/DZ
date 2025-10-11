package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.json.JSONObject
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now only contains StreamHG for this focused test.
val extractorList = listOf(
    StreamHG()
)

// Using a mobile User-Agent.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("Extractor", "safeGetAsDocument FAILED for $url: ${e.message}")
        null
    }
}

// The classes must be public to be accessible in the public `extractorList`.
abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    // Focusing only on kravaxxa.com
    private val potentialHosts = listOf(
        "kravaxxa.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("Extraction_Test", "================== getUrl CALLED v32 (Forensic Test) ==================")
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

            // Find all 'eval' scripts and pick the longest one.
            val packedJs = doc.select("script")
                .map { it.data() }
                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                .maxByOrNull { it.length }

            if (packedJs.isNullOrBlank()) continue

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("Extraction_Test", "Successfully unpacked JS. Now testing all extraction methods...")

                // =================== v32 FORENSIC TEST ===================

                // --- Method 1: Simple String Manipulation ---
                try {
                    val simpleStringResult = unpacked.substringAfter("\"hls2\":\"").substringBefore("\"")
                    if (simpleStringResult.isNotBlank() && simpleStringResult.startsWith("http")) {
                        Log.d("Extraction_Test", "Method 1 (Simple String) SUCCESS: ${simpleStringResult.take(100)}...")
                    } else {
                        Log.e("Extraction_Test", "Method 1 (Simple String) FAILED: Result was not a valid link -> '$simpleStringResult'")
                    }
                } catch (e: Exception) {
                    Log.e("Extraction_Test", "Method 1 (Simple String) CRASHED: ${e.message}")
                }

                // --- Method 2: Flexible Regex ---
                try {
                    val regex = Regex("""["']hls2["']\s*:\s*["'](.*?)["']""")
                    val regexResult = regex.find(unpacked)?.groupValues?.get(1)
                    if (regexResult != null && regexResult.startsWith("http")) {
                        Log.d("Extraction_Test", "Method 2 (Regex) SUCCESS: ${regexResult.take(100)}...")
                    } else {
                        Log.e("Extraction_Test", "Method 2 (Regex) FAILED: Regex did not find a valid link. Found: '$regexResult'")
                    }
                } catch (e: Exception) {
                    Log.e("Extraction_Test", "Method 2 (Regex) CRASHED: ${e.message}")
                }

                // --- Method 3: JSON Parsing ---
                try {
                    // Extract the JSON part from the "var links = {...};" script
                    val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";")
                    if (jsonObjectString.isNotBlank()) {
                        val jsonObject = JSONObject(jsonObjectString)
                        val jsonResult = jsonObject.getString("hls2")
                        Log.d("Extraction_Test", "Method 3 (JSON Parsing) SUCCESS: ${jsonResult.take(100)}...")
                    } else {
                        Log.e("Extraction_Test", "Method 3 (JSON Parsing) FAILED: Could not extract the JSON object string from the script.")
                    }
                } catch (e: Exception) {
                    Log.e("Extraction_Test", "Method 3 (JSON Parsing) CRASHED: ${e.message}")
                }
                
                // This version does not call the callback, it only logs the results.
                // ==========================================================

            } catch (e: Exception) {
                Log.e("Extraction_Test", "An error occurred during unpacking: ${e.message}")
            }
        }
        Log.d("Extraction_Test", "================== getUrl FINISHED (Forensic run complete) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
