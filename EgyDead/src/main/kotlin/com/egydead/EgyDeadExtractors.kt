package com.egydead

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities // Import for quality enum
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

private val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
    "Sec-Ch-Ua" to "\"Not/A)Brand\";v=\"99\", \"Google Chrome\";v=\"115\", \"Chromium\";v=\"115\"",
    "Sec-Ch-Ua-Mobile" to "?0",
    "Sec-Ch-Ua-Platform" to "\"Linux\"",
    "Sec-Fetch-Dest" to "iframe",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "cross-site",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
)

private val cloudflareKiller by lazy { CloudflareKiller() }

private suspend fun safeGetAsDocument(url: String, referer: String? = null): Document? {
    return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).document
    } catch (e: Exception) {
        Log.e("SafeGetAsDocument", "Request failed for $url. Error: ${e.message}")
        e.printStackTrace()
        null
    }
}

private suspend fun safeGetAsText(url: String, referer: String? = null): String? {
     return try {
        app.get(url, referer = referer, headers = BROWSER_HEADERS, interceptor = cloudflareKiller, verify = false).text
    } catch (e: Exception) {
        Log.e("SafeGetAsText", "Request failed for $url. Error: ${e.message}")
        e.printStackTrace()
        null
    }
}


private abstract class StreamHGBase(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true

    private val potentialHosts = listOf(
        "kravaxxa.com",
        "cavanhabg.com",
        "dumbalag.com",
        "davioad.com",
        "haxloppd.com"
    )

    // =================================================================================
    // START of v5 FIX
    // The extractor now calls the callback directly with the found m3u8 link
    // instead of passing the job to loadExtractor again. This fixes the timing issue
    // (race condition) where the parent function would finish before the link was processed.
    // =================================================================================
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d(name, "->->-> StreamHGBase getUrl function CALLED for URL: $url <-<-<-")

        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(name, "Could not extract video ID from $url")
            return
        }
        Log.d(name, "Extracted video ID: $videoId")

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            Log.d(name, "Attempting to access final page: $finalPageUrl")

            val finalPageDoc = safeGetAsDocument(finalPageUrl, referer = url)

            val packedJs = finalPageDoc?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs != null) {
                Log.d(name, "Success! Found packed JS on $finalPageUrl")
                val unpacked = getAndUnpack(packedJs)
                val m3u8Link = Regex("""(https?://.*?/master\.m3u8)""").find(unpacked)?.groupValues?.get(1)

                if (m3u8Link != null) {
                    Log.d(name, "Found m3u8 link: $m3u8Link. Calling callback directly.")
                    callback(
                        ExtractorLink(
                            this.name,                               // source name
                            this.name,                               // display name
                            m3u8Link,                                 // the URL
                            finalPageUrl,                             // referer
                            Qualities.Unknown.value,                  // quality, player will determine it
                            isM3u8 = true
                        )
                    )
                    // We found a working link, so we exit the loop and the function immediately.
                    return
                } else {
                    Log.e(name, "Found packed JS on $finalPageUrl but failed to extract m3u8 link.")
                }
            } else {
                Log.d(name, "Packed JS not found on $finalPageUrl, trying next host.")
            }
        }

        Log.e(name, "Failed to find a working link for video ID $videoId after trying all hosts.")
    }
    // =================================================================================
    // END of v5 FIX
    // =================================================================================
}

private class StreamHG : StreamHGBase("StreamHG", "hglink.to")
private class Davioad : StreamHGBase("StreamHG (Davioad)", "davioad.com")
private class Haxloppd : StreamHGBase("StreamHG (Haxloppd)", "haxloppd.com")
private class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
private class Cavanhabg : StreamHGBase("StreamHG (Cavanhabg)", "cavanhabg.com")
private class Dumbalag : StreamHGBase("StreamHG (Dumbalag)", "dumbalag.com" )

private class Forafile : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = safeGetAsDocument(url, referer)
        val packedJs = document?.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            // Forafile seems to use mp4 directly
            val mp4Link = Regex("""file:"(https?://.*?/video\.mp4)""").find(unpacked)?.groupValues?.get(1)
            if (mp4Link != null) {
                 callback(
                    ExtractorLink(
                        this.name,
                        this.name,
                        mp4Link,
                        url, // Referer is the forafile page itself
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            } else {
                 Log.e(name, "mp4 link not found in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found for $url. Cloudflare bypass likely failed.")
        }
    }
}

private abstract class DoodStreamBase : ExtractorApi() {
    override var name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val newUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val responseText = safeGetAsText(newUrl, referer)

        if (responseText.isNullOrBlank()) {
            Log.e(name, "Response text was null or blank for $newUrl.")
            return
        }

        val doodToken = responseText.substringAfter("'/pass_md5/").substringBefore("',")
        if (doodToken.isBlank()) {
            Log.e(name, "Could not find doodToken for $url.")
            return
        }
        val md5PassUrl = "https://${this.mainUrl}/pass_md5/$doodToken"
        // Doodstream requires a specific User-Agent for the final link
        val trueUrl = app.get(md5PassUrl, referer = newUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text + "z"
        callback(ExtractorLink(this.name, this.name, trueUrl, newUrl, Qualities.Unknown.value, isM3u8 = false))
    }
}
private class DoodStream : DoodStreamBase() { override var mainUrl = "doodstream.com" }
private class DsvPlay : DoodStreamBase() { override var mainUrl = "dsvplay.com" }

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
                callback(ExtractorLink(this.name, this.name, finalUrl, url, Qualities.Unknown.value))
            } else {
                Log.e(name, "Regex failed to find video URL in unpacked JS for $url")
            }
        } else {
             Log.e(name, "Packed JS not found for $url.")
        }
    }
}

private class Mixdrop : PackedJsExtractorBase("Mixdrop", "mixdrop.ag", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mdfx9dc8n : Packed_JsExtractorBase("Mdfx9dc8n", "mdfx9dc8n.net", """MDCore\.wurl="([^"]+)""".toRegex())
private class Mxdrop : Packed_JsExtractorBase("Mxdrop", "mxdrop.to", """MDCore\.wurl="([^"]+)""".toRegex())

private class Bigwarp : Packed_JsExtractorBase("Bigwarp", "bigwarp.com", """\s*file\s*:\s*"([^"]+)""".toRegex())
private class BigwarpPro : Packed_JsExtractorBase("Bigwarp Pro", "bigwarp.pro", """\s*file\s*:\s*"([^"]+)""".toRegex())

private open class PlaceholderExtractor(override var name: String, override var mainUrl: String) : ExtractorApi() {
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(name, "Extractor not yet implemented for $url")
    }
}

private class EarnVids : PlaceholderExtractor("EarnVids", "dingtezuni.com")
private class VidGuard : PlaceholderExtractor("VidGuard", "listeamed.net")
