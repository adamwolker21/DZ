package com.egydead.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
// FIXED: The 'Qualities' enum was moved to the 'utils' package in recent Cloudstream updates.
// Adding the correct import resolves the 'Unresolved reference' error.
import com.lagradost.cloudstream3.utils.Qualities

open class StreamHGExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
        if (packedJs != null) {
            val unpacked = getAndUnpack(packedJs)
            val m3u8Link = Regex("""sources:\[\{file:"(.*?)"\}\]""").find(unpacked)?.groupValues?.get(1)
            if (m3u8Link != null) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = httpsify(m3u8Link),
                        referer = referer ?: "",
                        // With the correct import, this now works as intended.
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                    )
                )
            }
        }
    }
}

open class ForafileExtractor : ExtractorApi() {
    override var name = "Forafile"
    override var mainUrl = "https://forafile.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val videoUrl = document.selectFirst("source")?.attr("src")
        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = referer ?: "",
                     // With the correct import, this now works as intended.
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }
    }
}
