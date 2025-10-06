package com.egydead.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify

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
                        this.name,
                        this.name,
                        httpsify(m3u8Link),
                        referer ?: "",
                        Qualities.Unknown.value,
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
                    this.name,
                    this.name,
                    videoUrl,
                    referer ?: "",
                    Qualities.Unknown.value,
                )
            )
        }
    }
}
