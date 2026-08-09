package com.krmzi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// مستخرج سيرفر Arab HD
open class ArabHdExtractor : ExtractorApi() {
    override var name: String = "Arab HD"
    override var mainUrl: String = "arabhd.onl"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, referer = "https://$mainUrl/")
            val doc = response.document
            
            val script = doc.select("script").find { it.data().contains("eval(function") }?.data() ?: ""
            val unpacked = if (script.isNotEmpty()) {
                JsUnpacker(script).unpack() ?: response.text
            } else {
                response.text
            }

            val m3u8Regex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val fallbackRegex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val match = m3u8Regex.find(unpacked) ?: fallbackRegex.find(unpacked)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                
                extractedLinks.add(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://$mainUrl",
                            "Referer" to "https://$mainUrl/"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (extractedLinks.isNotEmpty()) extractedLinks else null
    }
}

// مستخرج سيرفر Turk (مشابه جداً لـ Arab HD لكن مع نطاق مختلف)
class TurkExtractor : ExtractorApi() {
    override var name: String = "Turk"
    override var mainUrl: String = "arabveturk.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, referer = "https://$mainUrl/")
            val doc = response.document
            
            val script = doc.select("script").find { it.data().contains("eval(function") }?.data() ?: ""
            val unpacked = if (script.isNotEmpty()) {
                JsUnpacker(script).unpack() ?: response.text
            } else {
                response.text
            }

            val m3u8Regex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val fallbackRegex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val match = m3u8Regex.find(unpacked) ?: fallbackRegex.find(unpacked)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                
                extractedLinks.add(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://$mainUrl",
                            "Referer" to "https://$mainUrl/"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (extractedLinks.isNotEmpty()) extractedLinks else null
    }
}

// مستخرج سيرفر Red HD / IPlayerHLS
class RedHdExtractor : ExtractorApi() {
    override var name: String = "Red HD"
    override var mainUrl: String = "iplayerhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, referer = url)
            val doc = response.document
            
            // في بعض الأحيان iplayerhls يقوم بتشفير الرابط ب eval وأحياناً يضعه مباشرة
            val script = doc.select("script").find { it.data().contains("eval(function") }?.data() ?: ""
            val unpacked = if (script.isNotEmpty()) {
                JsUnpacker(script).unpack() ?: response.text
            } else {
                response.text
            }

            val m3u8Regex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val fallbackRegex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val match = m3u8Regex.find(unpacked) ?: fallbackRegex.find(unpacked)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                
                extractedLinks.add(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://$mainUrl",
                            "Referer" to url
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (extractedLinks.isNotEmpty()) extractedLinks else null
    }
}
