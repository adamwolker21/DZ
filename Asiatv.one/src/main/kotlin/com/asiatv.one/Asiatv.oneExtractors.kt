package com.asiatv.one

// Corrected imports for the latest CloudStream API structure
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName

// The base class is now ExtractorApi
open class AsiaTvPlayer : ExtractorApi() {
    // Set the name for the extractor, which will be displayed in the UI
    override var name = "AsiaTvPlayer"
    // The main URL of the extractor service
    override var mainUrl = "https://www.asiatvplayer.com"
    // The URL that will be used as a referer for video requests
    private val refererUrl = "$mainUrl/"
    override val requiresReferer = true


    // This function is called to extract video links from a given URL
    override suspend fun getUrl(
        url: String, // The embed URL e.g., https://www.asiatvplayer.com/embed-xxxx.html
        referer: String?, // The page that contains the embed URL
    ): List<ExtractorLink>? { // Return a list of links
        // Step 1: Get the HTML content of the embed page, using the provided referer
        val document = app.get(url, referer = referer).document

        // Step 2: Find the script tag containing the packed/obfuscated code
        val script = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null // Exit if the script is not found

        // Step 3: Use the JsUnpacker utility to deobfuscate the script
        val unpackedScript = JsUnpacker(script).unpack() ?: return null

        // Step 4: Use Regex to find all video links within the deobfuscated script
        val sources = mutableListOf<ExtractorLink>()

        // This regex captures the HLS (m3u8) master file
        val m3u8Regex = Regex("""file:\s*"([^"]+master\.m3u8)"""")
        m3u8Regex.find(unpackedScript)?.let {
            val m3u8Url = it.groupValues[1]
            // Use the direct ExtractorLink constructor - this is the new method
            sources.add(
                ExtractorLink(
                    source = this.name,
                    name = "HLS (Auto)",
                    url = m3u8Url,
                    referer = refererUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
        }

        // This regex captures the direct MP4 files with their corresponding labels (e.g., 720p)
        val mp4Regex = Regex("""file:\s*"([^"]+v\.mp4)",\s*label:\s*"([^"]+)"""")
        mp4Regex.findAll(unpackedScript).forEach { match ->
            val videoUrl = match.groupValues[1]
            val qualityLabel = match.groupValues[2] // e.g., "720p"
            sources.add(
                ExtractorLink(
                    source = this.name,
                    name = qualityLabel,
                    url = videoUrl,
                    referer = refererUrl,
                    quality = getQualityFromName(qualityLabel),
                    isM3u8 = false,
                )
            )
        }
        
        return sources // Return the list of extracted links
    }
                             }
