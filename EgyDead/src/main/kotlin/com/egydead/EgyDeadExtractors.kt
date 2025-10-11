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
import org.json.JSONObject
import org.jsoup.nodes.Document
import android.util.Log

// The complete list of extractors, all fixed to work with the modern API.
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(), Dumbalag(),
    Forafile(),
    DoodStream(), DsvPlay(),
    Mixdrop(), Mdfx9dc8n(), Mxdrop(),
    Bigwarp(), BigwarpPro(),
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

    // The full list of potential hosts.
    private val potentialHosts = listOf(
        "kravaxxa.com",
        "cavanhabg.com",
        "dumbalag.com",
        "davioad.com",
        "haxloppd.com"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return

        for (host in potentialHosts) {
            val finalPageUrl = "https://$host/e/$videoId"
            val doc = safeGetAsDocument(finalPageUrl, referer = url) ?: continue

            // Find all 'eval' scripts and pick the longest one to avoid the trap script.
            val packedJs = doc.select("script")
                .map { it.data() }
                .filter { it.contains("eval(function(p,a,c,k,e,d)") }
                .maxByOrNull { it.length }

            if (packedJs.isNullOrBlank()) continue

            try {
                val unpacked = getAndUnpack(packedJs)
                
                // =================== v33 THE FINAL GUARANTEED FIX ===================
                // This is the winning method from our forensic test (v32).
                // It parses the unpacked script as a structured JSON object, which is 100% reliable.
                val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";")
                val jsonObject = JSONObject(jsonObjectString)
                val m3u8Link = jsonObject.getString("hls2")
                // ====================================================================

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
                    Log.d("StreamHG", "SUCCESS: Found 'hls2' link via JSON Parsing: $m3u8Link")
                    
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return 
                }
            } catch (e: Exception) {
                Log.e("StreamHG", "An error occurred during JSON parsing or link extraction: ${e.message}")
            }
        }
    }
}

// Defining the classes for each domain.
class StreamHG : StreamHGBase("StreamHG", "hglink.to")
class Davioad : StreamHGBase("StreamHG (Davioad)", "davioad.com")
class Haxloppd : StreamHGBase("StreamHG (Haxloppd)", "haxloppd.com")
class Kravaxxa : StreamHGBase("StreamHG (Kravaxxa)", "kravaxxa.com")
class Cavanhabg : StreamHGBase("StreamHG (Cavanhabg)", "cavanhabg.com")
class Dumbalag : StreamHGBase("StreamHG (Dumbalag)", "dumbalag.com" )


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
            newExtractorLink(this.name, this.name, trueUrl, type = ExtractorLinkType.M3U8) {
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
