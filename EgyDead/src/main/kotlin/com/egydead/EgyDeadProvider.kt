package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class EgyDeadProvider : MainAPI() {
    override var name = "EgyDead"
    override var mainUrl = "https://tv5.egydead.live"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Headers محسنة لمحاكاة متصفح الهاتف
    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "https://tv5.egydead.live/",
        "Origin" to "https://tv5.egydead.live",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun getStatus(element: Element?): ShowStatus {
        return when {
            element?.text()?.contains("مكتمل", true) == true -> ShowStatus.Completed
            element?.text()?.contains("مستمر", true) == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            val linkElement = this.selectFirst("a") ?: return null
            val href = fixUrlNull(linkElement.attr("href")) ?: return null
            
            // استخراج العنوان من مصادر متعددة
            val title = linkElement.selectFirst("h1.BottomTitle")?.text() 
                ?: linkElement.attr("title") 
                ?: linkElement.selectFirst("img")?.attr("alt")
                ?: return null

            // استخراج صورة البوستر
            val posterUrl = fixUrlNull(linkElement.selectFirst("img")?.attr("src"))

            // تحديد نوع المحتوى بناءً على الفئة أو الرابط
            val category = linkElement.selectFirst("span.c
