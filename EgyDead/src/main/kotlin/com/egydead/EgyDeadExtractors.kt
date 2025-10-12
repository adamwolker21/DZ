package com.egydead

import android.util.Log // Added for logging
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document

// The list is updated with the new, specialized Dingtezuni extractor.
val extractorList = listOf(
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
    Dingtezuni(), // Replaced the old EarnVids with the new specific extractor
)

// Using a mobile User-Agent.
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar=q=0.8",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

// Safe function to get page as Document.
private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        null
    }
}

// --- New Specialized Extractor for Dingtezuni / EarnVids with Detailed Logging ---
class Dingtezuni : ExtractorApi() {
    override var name = "EarnVids" // We can keep the name user-friendly
    override var mainUrl = "dingtezuni.com"
    override val requiresReferer = true

    companion object {
        // TAG for organized logging
        private const val TAG = "DingtezuniExtractor"
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d(TAG, "Extractor invoked for URL: $url")

        val document = safeGetAsDocument(url, referer)
        if (document == null) {
            Log.e(TAG, "Failed to get document from URL: $url")
            return
        }
        Log.d(TAG, "Successfully fetched document.")

        val packedJs = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs == null || packedJs.isBlank()) {
            Log.e(TAG, "Could not find the packed JS script in the page.")
            return
        }
        Log.d(TAG, "Found packed JS script.")

        try {
            val unpackedJs = getAndUnpack(packedJs)
            Log.d(TAG, "Unpacked JS successfully. Content length: ${unpackedJs.length}")

            val hls2Regex = Regex(""""hls2":\s*"([^"]+)"""")
            val matchResult = hls2Regex.find(unpackedJs)

            if (matchResult == null) {
                Log.e(TAG, "Regex did not find a match for 'hls2' link.")
                // Log the full unpacked script ONLY if the regex fails, for easier debugging.
                Log.d(TAG, "Unpacked JS Content for debugging: $unpackedJs")
                return
            }

            val hls2Link = matchResult.groupValues[1]
            Log.d(TAG, "Successfully extracted hls2 link: $hls2Link")

            if (hls2Link.isNotBlank()) {
                Log.d(TAG, "Invoking callback with the extracted link.")
                callback(
                    newExtractorLink(this.name, this.name, hls2Link) {
                        // This link works directly without a referer, as you tested
                        this.referer = "" 
                    }
                )
            } else {
                Log.e(TAG, "Extracted hls2 link is blank.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "An error occurred during unpacking or regex matching: ${e.message}", e)
        }
    }
}


// --- Forafile Handler (Fixed) ---
class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer)
        val packedJs = document?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                callback(
                    newExtractorLink(this.name, this.name, mp4Link) {
                        this.referer = url
                    }
                )
            }
        }
    }
}

// --- DoodStream Handlers (Fixed) ---
abstract class DoodStreamBase : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = try { app.get(newUrl, referer = referer, headers = BROWSER_HEADERS).text } catch (e: Exception) { null } ?: return

        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) return
        
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
        callback(
            newExtractorLink(this.name, this.name, trueUrl) {
                this.referer = newUrl
            }
        )
    }
}
class DoodStream : DoodStreamBase() { override var name = "DoodStream"; override var mainUrl = "doodstream.com" }
class DsvPlay : DoodStreamBase() { override var name = "DsvPlay"; override var mainUrl = "dsvplay.com" }


// --- Packed JS Extractor Base (Fixed) ---
abstract class PackedJsExtractorBase(
    override var name: String,
    override var mainUrl: String,
    private val regex: Regex
) : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = safeGetAsDocument(url, referer)
        val script = doc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
        if (script != null) {
            val unpacked = getAndUnpack(script)
            val videoUrl = regex.find(unpacked)?.groupValues?.get(1)
            if (videoUrl != null && videoUrl.isNotBlank()) {
                val finalUrl = if (videoUrl.startsWith("//")) "https:${videoUrl}" else videoUrl
                callback(
                    newExtractorLink(this.name, this.name, finalUrl) {
                        this.referer = url
                    }
                )
            }
        }
    }
}

class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())

class Bigwarp : PackedJsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())
