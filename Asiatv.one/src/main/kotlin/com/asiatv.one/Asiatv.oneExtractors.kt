package com.asiatv.one

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.extractors.MultiQuality
import com.lagradost.cloudstream3.base64Decode
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.getAndUnpack
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.M3u8Load
import com.lagradost.cloudstream3.unM3u8
import com.lagradost.cloudstream3.scripting.safeUnpack
import com.lagradost.cloudstream3.extractors.Vidstream

// We use QuickExtractor as it's the modern and stable way
open class AsiaTvPlayer : QuickExtractor() {
    override var name = "AsiaTvPlayer"
    override var mainUrl = "https://www.asiatvplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val embedHeaders = mapOf("Referer" to (referer ?: "https://asiawiki.me/"))
        val document = app.get(url, headers = embedHeaders).document
        
        val script = document.selectFirst("script:containsData(eval)")?.data() ?: return null
        val unpackedScript = getAndUnpack(script)
        
        // This will be our list of links to return
        val links = mutableListOf<ExtractorLink>()

        // Extract direct MP4 links
        val mp4Regex = Regex("""file:"([^"]+\.mp4)"\s*,\s*label:"([^"]+)"""")
        mp4Regex.findAll(unpackedScript).forEach { match ->
            val fileUrl = match.groupValues[1]
            val qualityLabel = match.groupValues[2]
            links.add(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} MP4",
                    url = fileUrl,
                    referer = url,
                    quality = when {
                        qualityLabel.contains("720") -> Qualities.P720.value
                        qualityLabel.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    },
                    isM3u8 = false
                )
            )
        }

        // Extract m3u8 link and generate qualities from it
        val m3u8Url = Regex("""file:"(.*?(?:\.m3u8))"""").find(unpackedScript)?.groupValues?.get(1)
        if (m3u8Url != null) {
            val m3u8Headers = mapOf(
                "Origin" to this.mainUrl,
                "Referer" to this.mainUrl + "/"
            )
            // M3u8Helper will return a list of ExtractorLinks
            M3u8Helper.generateM3u8(
                name = this.name,
                streamUrl = m3u8Url,
                referer = this.mainUrl + "/",
                headers = m3u8Headers
            ).forEach { link ->
                links.add(link)
            }
        }
        
        return links
    }
                            }
