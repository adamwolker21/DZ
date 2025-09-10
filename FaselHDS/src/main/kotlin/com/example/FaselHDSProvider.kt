 
package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FaselHDSProvider : MainAPI() {
    // ✨ المعلومات الأساسية
    override var mainUrl = "https://www.faselhds.life"
    override var name = "FaselHDS"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // 📄 أقسام الصفحة الرئيسية
    override val mainPage = mainPageOf(
        "/movies" to "أحدث الأفلام",
        "/series" to "أحدث المسلسلات",
        "/genre/افلام-انمي" to "أفلام أنمي",
        "/genre/افلام-اسيوية" to "أفلام أسيوية",
        "/genre/افلام-تركية" to "أفلام تركية"
    )

    // 🏠 جلب محتوى الصفحة الرئيسية
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}/page/$page"
        val document = app.get(url).document
        // استهداف العناصر التي تحتوي على الأفلام أو المسلسلات
        val home = document.select("div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // 📝 تحويل عنصر HTML إلى نتيجة بحث
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3 a")?.text() ?: "No Title"
        // استخراج الصورة من عنصر style background-image
        val posterUrl = this.selectFirst("div.post-thumb a")
            ?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // 🔍 البحث
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.post-listing article.item-list").mapNotNull {
            it.toSearchResult()
        }
    }

    // 🎬 تحميل تفاصيل الفيلم أو المسلسل
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.title-container h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
        val yearText = document.select("div.meta-bar span.year").firstOrNull()?.text()
        val year = yearText?.toIntOrNull()


        // التحقق إذا كان المحتوى مسلسلاً أم فيلماً
        val isTvSeries = document.select("div#season-list").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // استهداف أزرار المواسم
            document.select("div.season-list-item a").forEach { seasonLink ->
                val seasonUrl = seasonLink.attr("href")
                val seasonDoc = app.get(seasonUrl).document
                val seasonNumText = seasonDoc.selectFirst("h2.entry-title")?.text()
                val seasonNum = Regex("""الموسم (\d+)""").find(seasonNumText ?: "")?.groupValues?.get(1)?.toIntOrNull()

                // استهداف روابط الحلقات داخل الموسم
                seasonDoc.select("div.ep-item a").forEach { episodeLink ->
                    val epHref = episodeLink.attr("href")
                    val epTitle = episodeLink.select("span.ep-title").text()
                    val epNum = episodeLink.select("span.ep-num").text().toIntOrNull()

                    episodes.add(
                        Episode(
                            data = epHref,
                            name = epTitle,
                            season = seasonNum,
                            episode = epNum,
                        )
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            // استهداف سيرفرات المشاهدة للفيلم
            val watchLinks = document.select("ul.quality-list li a").map {
                val embedUrl = it.attr("data-url")
                // اسم السيرفر + الجودة
                val name = it.text()
                // تخزين الرابط في an Episode object
                Episode(data = embedUrl, name = name)
            }

            return newMovieLoadResponse(title, url, TvType.Movie, watchLinks) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
    }

    // 🔗 استخراج روابط الفيديو النهائية
    override suspend fun loadLinks(
        data: String, // هنا هو الرابط الوسيط (embedUrl) من دالة load
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data هو رابط صفحة المشاهدة مثل https://www.faselhds.life/embed/...
        val embedPage = app.get(data, referer = "$mainUrl/").document
        // البحث عن رابط iframe داخل الصفحة الوسيطة
        val iframeSrc = embedPage.selectFirst("iframe")?.attr("src") ?: return false

        // استخدام loadExtractor المدمج في CloudStream لجلب الرابط من السيرفرات المعروفة
        // مثل Uqload, Doodstream, etc.
        loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)

        return true
    }
}
