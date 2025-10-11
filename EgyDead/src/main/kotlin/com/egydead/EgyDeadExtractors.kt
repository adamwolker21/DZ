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

// As requested for the test, this file only contains the StreamHG extractor.
val extractorList = listOf(
    StreamHG(), Davioad(), Haxloppd(), Kravaxxa(), Cavanhabg(), Dumbalag(),
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
                
                // The final, guaranteed fix: Extract the JSON object and parse it.
                // The .trim() is crucial to remove leading whitespace.
                val jsonObjectString = unpacked.substringAfter("var links = ").substringBefore(";").trim()
                val jsonObject = JSONObject(jsonObjectString)
                val m3u8Link = jsonObject.getString("hls2")

                if (m3u8Link.isNotBlank() && m3u8Link.startsWith("http")) {
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
                    // Once we find a working link, we can stop searching other hosts.
                    return 
                }
            } catch (e: Exception) {
                Log.e("StreamHG", "Failed on host '$host': ${e.message}")
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
