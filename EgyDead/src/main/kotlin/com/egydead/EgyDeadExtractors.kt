package com.egydead

// Correct and complete list of imports
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.newExtractorLink

// Helper function now available to all extractors in this file
private fun String.getQualityFromString(): Int {
    return Regex("(\\d{3,4})[pP]").find(this)?.groupValues?.get(1)?.toIntOrNull()
        ?: this.toIntOrNull()
        ?: Qualities.Unknown.value
}

// Base class for extractors that use packed JS
open class PackedExtractor(
    override var name: String,
    vararg val domains: String
) : ExtractorApi() {
    override var mainUrl = domains[0]
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url, referer = referer).document
        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""(https?:\/\/[^\s'"]*master\.m3u8[^\s'"]*)""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                return M3u8Helper.generateM3u8(
                    this.name,
                    m3u8Link,
                    url, // referer
                    headers = emptyMap()
                )
            }
        }
        return null
    }
}

// Specific extractor implementations
class StreamHGExtractor : PackedExtractor("StreamHG", "hglink.to", "davioad.com", "haxloppd.com", "kravaxxa.com", "dumbalag.com")
class EarnVidsExtractor : PackedExtractor("EarnVids", "dingtezuni.com")

class ForafileExtractor : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "forafile.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = app.get(url).document
        val videoUrl = document.selectFirst("source, video")?.attr("src")
        if (videoUrl != null && videoUrl.endsWith(".mp4")) {
             return listOf(
                 newExtractorLink(this.name, this.name, videoUrl, type = ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
             )
        }
        return null
    }
}

class BigwarpExtractor : ExtractorApi() {
    override var name = "Bigwarp"
    override var mainUrl = "bigwarp.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url).document
        val jwplayerScript = doc.selectFirst("script:containsData(jwplayer(\"vplayer\").setup)")?.data() ?: return null

        val sourcesRegex = Regex("""sources:\s*\[(.+?)\]""")
        val sourcesBlock = sourcesRegex.find(jwplayerScript)?.groupValues?.get(1) ?: return null

        val fileRegex = Regex("""\{file:"([^"]+)",label:"([^"]+)"\}""")
        return fileRegex.findAll(sourcesBlock).map { match ->
            val videoUrl = match.groupValues[1]
            val qualityLabel = match.groupValues[2]
            newExtractorLink(
                this.name,
                "${this.name} ${qualityLabel}",
                videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = qualityLabel.getQualityFromString()
            }
        }.toList()
    }
}

class VidGuardExtractor : ExtractorApi() {
    override var name = "VidGuard"
    override var mainUrl = "listeamed.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url, referer = referer).document
        val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return null

        val playerDoc = app.get(iframeSrc, referer = url).document
        val packedJs = playerDoc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""(https?:\/\/[^\s'"]*master\.m3u8[^\s'"]*)""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                return M3u8Helper.generateM3u8(
                    this.name,
                    m3u8Link,
                    iframeSrc,
                    headers = emptyMap()
                )
            }
        }
        return null
    }
}
