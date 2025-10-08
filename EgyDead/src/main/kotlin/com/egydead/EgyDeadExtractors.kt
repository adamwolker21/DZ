package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import android.util.Log

// The list of extractors, used by the provider
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(), Dumbalag(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
    EarnVids(),
    VidGuard()
)

// --- Full headers to perfectly mimic a browser, based on user's cURL data ---
private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
    "Sec-Ch-Ua-Mobile" to "?0",
    "Sec-Ch-Ua-Platform" to "\"Linux\"",
    "Sec-Fetch-Dest" to "iframe",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "cross-site",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
)

// The definitive solution: A dedicated safe networking function with CloudflareKiller
private val cloudflareKiller by lazy { CloudflareKiller() }

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller).document
    } catch (e: Exception) {
        Log.e("SafeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        null
    }
}

private suspend fun safeGetAsText(url: String, referer: String? = null): String? {
     return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller).text
    } catch (e: Exception) {
        Log.e("SafeGetAsText", "Request failed for $url. Error: ${e.message}")
        null
    }
}

// =================================================================================================
// START OF THE MANUAL REDIRECT STREAMHG EXTRACTOR (v36)
// =================================================================================================
private abstract class StreamHGBase : ExtractorApi() {
    override var name = "StreamHG"
    override val requiresReferer = true

    // This list contains all possible domains the video might be hosted on.
    private val potentialHosts = listOf(
        "kravaxxa.com",
        "cavanhabg.com",
        "dumbalag.com",
        "davioad.com",
        "haxloppd.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Diagnostic Log: Check if this function is ever called.
        Log.d("StreamHG_Extractor", "->->-> StreamHGBase getUrl function CALLED for URL: $url <-<-<-")

        // Step 1: Extract the unique video ID from the original URL.
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(name, "Could not extract video ID from $url")
            return
        }
        Log.d(name, "Extracted video ID: $videoId")

        // Step 2: Loop through each potential host and try to find the video.
        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d(name, "Attempting to access final page: $finalPageUrl")

            // The referer for this request should be the original hglink url
            val finalPageDoc = safeGetAsDocument(finalPageUrl, url)

            // Step 3: Check if this page contains the packed JS. If not, continue to the next host.
            val packedJs = finalPageDoc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs != null) {
                Log.d(name, "Success! Found packed JS on $finalPageUrl")
                val unpacked = getAndUnpack(packedJs)
                val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)
                
                if (m3u8Link != null) {
                    Log.d(name, "Found m3u8 link: $m3u8Link")
                    // Use the final page URL as the referer for the video stream
                    loadExtractor(m3u8Link, finalPageUrl, subtitleCallback, callback)
                    // Once we find the link, we stop the loop immediately.
                    return
                } else {
                    Log.e(name, "Found packed JS on $finalPageUrl but failed to extract m3u8 link.")
                }
            } else {
                Log.d(name, "Packed JS not found on $finalPageUrl, trying next host.")
            }
        }

        // If the loop finishes without finding any links
        Log.e(name, "Failed to find a working link for video ID $videoId after trying all hosts.")
    }
}

// Add the new domain to the list of classes that use this logic
private class StreamHG : StreamHGBase() { override var mainUrl = "hglink.to" }
private class Davioad : StreamHGBase() { override var mainUrl = "davioad.com" }
private class Haxloppd : StreamHGBase() { override var mainUrl = "haxloppd.com" }
private class Kravaxxa : StreamHGBase() { override var mainUrl = "kravaxxa.com" }
private class Cavanhabg : StreamHGBase() { override var mainUrl = "cavanhabg.com"}
private class Dumbalag : StreamHGBase() { override var mainUrl = "dumbalag.com" }

// =================================================================================================
// END OF REVISED STREAMHG EXTRACTOR
// =================================================================================================


// --- Forafile Handler ---
private class Forafile : ExtractorApi() {
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
                loadExtractor(mp4Link, url, subtitleCallback, callback)
            } else {
                 Log.e(name, "mp4 link not found in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found for $url. Cloudflare bypass likely failed.")
        }
    }
}

// --- DoodStream Handlers ---
private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = safeGetAsText(newUrl, referer)

        if (responseText.isNullOrBlank()) {
            Log.e(name, "Response text was null or blank for $newUrl. Cloudflare bypass likely failed.")
            return
        }

        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) {
            Log.e(name, "Could not find doodToken for $url.")
            return
        }
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        val trueUrl = app.get(md5PassUrl, referer = newUrl).text + "z"
        loadExtractor(trueUrl, newUrl, subtitleCallback, callback)
    }
}
private class DoodStream : DoodStreamBase() { override var mainUrl = "doodstream.com" }
private class DsvPlay : DoodStreamBase() { override var mainUrl = "dsvplay.com" }

// --- Packed JS Extractor Base for Mixdrop, Bigwarp, etc. ---
private abstract class PackedJsExtractorBase(
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
                loadExtractor(finalUrl, url, subtitleCallback, callback)
            } else {
                Log.e(name, "Regex failed to find video URL in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found for $url. Cloudflare bypass likely failed.")
        }
    }
}

private class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mdfx9dc8n : PackedJsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mxdrop : PackedJsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())

private class Bigwarp : PackedJsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
private class BigwarpPro : PackedJsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())

// Placeholder extractors for new servers - they won't work yet but prevent crashes
private open class PlaceholderExtractor(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(name, "Extractor not yet implemented for $url")
    }
}

private class EarnVids : PlaceholderExtractor("EarnVids", "dingtezuni.com")
private class VidGuard : PlaceholderExtractor("VidGuard", "listeamed.net")
