package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.apmap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// تم تغيير اسم الكلاس ليتبع النمط المعتاد للمزودين
class Asia2TvProvider : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else request.data

        val document = app.get(url).document

        // التعامل مع الصفحات التالية للتصنيفات
        if (page > 1) {
            val items = document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
            // تم استخدام الدالة الجديدة لإنشاء الاستجابة
            return newHomePageResponse(
                listOf(HomePageList(request.name, items, isHorizontal = true)),
                hasNext = items.isNotEmpty()
            )
        }

        // التعامل مع الصفحة الرئيسية (page 1)
        val allhome = mutableListOf<HomePageList>()
        document.select("div.Blocks").forEach { section ->
            val title = section.selectFirst("div.title-bar h2")?.text() ?: return@forEach
            val categoryUrl =
                section.selectFirst("div.title-bar a.more")?.attr("href") ?: return@forEach
            val items = section.select("div.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                // تم استبدال 'data' بـ 'url' في المُنشئ الخاص بـ HomePageList
                allhome.add(HomePageList(title, items, url = categoryUrl))
            }
        }
        // تم استخدام الدالة الجديدة لإنشاء الاستجابة
        return newHomePageResponse(allhome, hasNext = true)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val posterDiv = this.selectFirst("div.poster") ?: return null
        val link = posterDiv.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("div.data h2 a")?.text() ?: return null
        val posterUrl =
            posterDiv.selectFirst("img")?.attr("data-src") ?: posterDiv.selectFirst("img")
                ?.attr("src")

        // تم استبدال المُنشئات القديمة بالدوال المساعدة الجديدة
        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.story p")?.text()?.trim()
        val year = document.select("div.meta span a[href*=release]").first()?.text()?.toIntOrNull()
        val tags = document.select("div.meta span a[href*=genre]").map { it.text() }
        val rating = document.selectFirst("div.imdb span")?.text()?.let {
            // التقييم في Cloudstream يكون من 1000, والمواقع تعرضه من 10
            (it.toFloatOrNull()?.times(100))?.toInt()
        }
        val recommendations = document.select("div.related div.item").mapNotNull {
            it.toSearchResponse()
        }

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                // تم استبدال 'rating' القديم بـ 'score'
                this.score = rating
                this.recommendations = recommendations
            }
        } else {
            val episodes = mutableListOf<Episode>()
            document.select("div#seasons div.season_item")
                .forEachIndexed { seasonIndex, seasonElement ->
                    val seasonNum =
                        seasonElement.selectFirst("h3")?.text()?.filter { it.isDigit() }
                            ?.toIntOrNull() ?: (seasonIndex + 1)
                    seasonElement.select("ul.episodes li").forEach { episodeElement ->
                        val epLink = episodeElement.selectFirst("a") ?: return@forEach
                        val epHref = fixUrl(epLink.attr("href"))
                        val epName = epLink.text()
                        val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                        // تم استخدام الدالة الجديدة newEpisode
                        episodes.add(newEpisode(epHref) {
                            name = epName
                            season = seasonNum
                            episode = epNum
                        })
                    }
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                // تم استبدال 'rating' القديم بـ 'score'
                this.score = rating
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        // تم تصحيح الخطأ الرئيسي هنا: كان يتم تمرير عنصر iframe بدلاً من الرابط (src)
        // الآن يتم استخراج الرابط من السمة 'src' قبل تمريره
        document.select("div.servers-list iframe").apmap { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                // استخدام 'data' (رابط الصفحة) كـ referer هو أفضل ممارسة
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.items div.item").mapNotNull { it.toSearchResponse() }
    }
}
