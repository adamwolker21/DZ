package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Document
import android.util.Log

// The extractor list now only contains StreamHG to focus on it.
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
        Log.e("StreamHG_Final", "safeGetAsDocument FAILED for $url: ${e.message}")
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
        Log.d("StreamHG_Final", "================== getUrl CALLED v29 (Diagnostic) ==================")
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("StreamHG_Final", "Failed to extract video ID.")
            return
        }

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d("StreamHG_Final", "Constructed final page URL: $finalPageUrl")

            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue
            Log.d("StreamHG_Final", "Successfully retrieved document.")

            // Find all 'eval' scripts and pick the longest one.
            val packedJs = doc.select("script")
                .map { it.data() }
                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                .maxByOrNull { it.length }

            if (packedJs == null || packedJs.isBlank()) {
                Log.e("StreamHG_Final", "Could not find any packed JS (eval) script.")
                continue
            }
            Log.d("StreamHG_Final", "Found the longest packed JS script (length: ${packedJs.length}). Attempting to unpack...")

            try {
                val unpacked = getAndUnpack(packedJs)
                Log.d("StreamHG_Final", "Successfully unpacked JS. Now logging its full content...")

                // =================== v29 DIAGNOSTIC CODE ===================
                // Log the entire unpacked content in chunks to avoid truncation.
                if (unpacked.isNotBlank()) {
                    Log.d("Phase3_Full_Unpacked_JS", "================== START OF UNPACKED CONTENT ==================")
                    unpacked.chunked(4000).forEachIndexed { index, chunk ->
                        Log.d("Phase3_Full_Unpacked_JS", "Chunk ${index + 1}: $chunk")
                    }
                    Log.d("Phase3_Full_Unpacked_JS", "==================  END OF UNPACKED CONTENT  ==================")
                } else {
                    Log.e("StreamHG_Final", "Unpacking was successful, but the result is an empty string.")
                }
                // ==========================================================

            } catch (e: Exception) {
                Log.e("StreamHG_Final", "An error occurred during unpacking: ${e.message}")
            }
        }
        Log.d("StreamHG_Final", "================== getUrl FINISHED (Diagnostic run complete) ==================")
    }
}

class StreamHG : StreamHGBase("StreamHG", "hglink.to")
