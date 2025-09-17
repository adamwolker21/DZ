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
            val category = linkElement.selectFirst("span.cat_name")?.text() ?: ""
            val isMovie = category.contains("أفلام", true) || 
                         category.contains("فيلم", true) ||
                         href.contains("/movie/", true) ||
                         href.contains("/film/", true) ||
                         href.contains("/category/", true)

            return if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { 
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            return null
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
        
        val document = app.get(url, headers = mobileHeaders).document

        // استخدام selectors مختلفة لتغطية الهياكل المختلفة
        val items = document.select("li.movieItem, .movieItem, ul.posts-list li, .catHolder li")
            .mapNotNull { it.toSearchResponse() }

        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeToUrl()}"
        val document = app.get(url, headers = mobileHeaders).document

        return document.select("li.movieItem, .movieItem, ul.posts-list li, .catHolder li")
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mobileHeaders).document

        // استخراج العنوان من مصادر متعددة
        val title = document.selectFirst("h1.entry-title, div.singleTitle span em, .singleTitle em")?.text()?.trim() 
            ?: "No Title"
        
        // استخراج القصة
        var plot = document.selectFirst("div.extra-content p, .extra-content p, .story p")?.text()?.trim()

        // استخراج البوستر من مصادر متعددة
        val posterUrl = fixUrlNull(
            document.selectFirst("div.single-thumbnail img, .single-thumbnail img, .poster img")?.attr("src")
        )

        // استخراج المعلومات من الجدول
        val details = document.select("div.LeftBox li, .LeftBox li, .info li")
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
                    val ratingFloat = ratingText.toFloatOrNull() ?: 0f
                    rating = (ratingFloat * 100).toInt()
                }
                text.contains("الحاله") || text.contains("الحالة") -> {
                    status = getStatus(li)
                }
            }
        }

        // الحصول على الحلقات (للمسلسلات)
        val episodes = document.select("div.episodeList a, div.episodes a, .episode a").mapNotNull { a ->
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
        val document = app.get(data, headers = mobileHeaders).document
        
        var foundLinks = false
        
        // البحث عن زر المشاهدة ونموذج الفيديو
        val watchButton = document.selectFirst("button:contains(المشاهده), button:contains(المشاهدة), input[value*='مشاهده']")
        
        if (watchButton != null) {
            // إذا كان هناك نموذج مخفي، نحتاج إلى إرسال طلب POST
            val form = watchButton.closest("form") ?: document.selectFirst("form")
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
                        headers = mobileHeaders + mapOf(
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
                    // تجاهل الخطأ والمحاولة بطرق أخرى
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
        
        // إذا لم نجد أي iframes، نحاول loadExtractor مباشرة على الصفحة
        if (!foundLinks) {
            loadExtractor(data, data, subtitleCallback, callback)
            foundLinks = true
        }
        
        return foundLinks
    }

    // دالة مساعدة للترميز
    private fun String.encodeToUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

class EgyDeadPlugin : CloudstreamPlugin() {
    override fun load(context: Context) = EgyDeadProvider()
}
