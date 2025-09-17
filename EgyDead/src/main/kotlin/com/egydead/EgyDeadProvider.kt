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

    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun getStatus(element: Element?): ShowStatus {
        return when {
            element?.text()?.contains("مكتمل", true) == true -> ShowStatus.Completed
            element?.text()?.contains("مستمر", true) == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val title = linkElement.selectFirst("h1.BottomTitle")?.text() ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        val isMovie = this.selectFirst("span.cat_name")?.text()?.contains("أفلام", true) == true

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override val mainPage = mainPageOf(
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-1/" to "مسلسلات أجنبية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d8%a7/" to "مسلسلات تركية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        
        val document = app.get(url, headers = customHeaders).document

        val items = document.select("li.movieItem").mapNotNull {
            it.toSearchResponse()
        }

        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = customHeaders).document

        return document.select("li.movieItem").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = customHeaders).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        var plot = document.selectFirst("div.extra-content p")?.text()?.trim()

        val posterUrl = fixUrlNull(document.selectFirst("div.single-thumbnail img")?.attr("src"))

        // استخراج المعلومات من الجدول
        val details = document.select("div.LeftBox li")
        var year: Int? = null
        var rating: Int? = null
        var country: String? = null
        var status: ShowStatus? = null
        val tags = mutableListOf<String>()

        details.forEach { li ->
            val text = li.text()
            when {
                text.contains("السنه") || text.contains("سنة") -> {
                    year = li.selectFirst("a")?.text()?.toIntOrNull()
                }
                text.contains("البلد") -> {
                    country = li.selectFirst("a")?.text()?.trim()
                }
                text.contains("النوع") -> {
                    tags.addAll(li.select("a").map { it.text().trim() })
                }
                text.contains("التقييم") -> {
                    val ratingText = li.text().replace(Regex("[^0-9.]"), "")
                    rating = (ratingText.toFloatOrNull() ?: 0f).times(100).toInt()
                }
                text.contains("الحاله") || text.contains("الحالة") -> {
                    status = getStatus(li)
                }
            }
        }

        // الحصول على الحلقات
        val episodes = document.select("div.episodeList a, div.episodes a").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val epNumText = a.selectFirst("span.epNum")?.text() ?: a.text()
            val epNum = epNumText.replace(Regex("[^0-9]"), "").toIntOrNull()

            newEpisode(href) {
                name = a.selectFirst("span.epTitle")?.text() ?: "الحلقة ${epNum ?: ""}"
                episode = epNum
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.rating = rating
                this.showStatus = status
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.rating = rating
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = customHeaders).document
        
        // البحث عن زر المشاهدة ونموذج الفيديو
        val watchButton = document.selectFirst("button:contains(المشاهده), button:contains(المشاهدة)")
        var foundLinks = false
        
        if (watchButton != null) {
            // إذا كان هناك نموذج مخفي، نحتاج إلى إرسال طلب POST
            val form = watchButton.closest("form")
            if (form != null) {
                try {
                    val action = form.attr("action").takeIf { it.isNotBlank() } ?: data
                    val inputs = form.select("input")
                    val formData = mutableMapOf<String, String>()
                    
                    inputs.forEach { input ->
                        val name = input.attr("name")
                        val value = input.attr("value")
                        if (name.isNotBlank()) {
                            formData[name] = value
                        }
                    }
                    
                    // إضافة قيمة الزر إذا كانت موجودة
                    formData["View"] = "1"
                    
                    val response = app.post(
                        action,
                        data = formData,
                        referer = data,
                        headers = customHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).text
                    
                    // محاولة استخراج iframe من الاستجابة
                    val iframeSrc = Jsoup.parse(response).selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    
                } catch (e: Exception) {
                    println("Error processing video form: ${e.message}")
                }
            }
        }
        
        // إذا لم نجد عبر النموذج، نحاول البحث عن iframes مباشرة
        if (!foundLinks) {
            val iframes = document.select("iframe")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }

    data class NewPlayerAjaxResponse(
        val status: Boolean,
        val codeplay: String
    )
}
