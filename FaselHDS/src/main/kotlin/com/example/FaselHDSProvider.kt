package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class FaselHDSProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://www.faselhds.life"
    override var name = "FaselHDS"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val lastValidUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    companion object {
        var activeBaseUrl: String? = null
    }

    private suspend fun getBaseUrl(): String {
        activeBaseUrl?.let { return it }
        return try {
            val response = app.get(mainUrl)
            val finalUrl = response.url
            val uri = java.net.URI(finalUrl)
            val base = "${uri.scheme}://${uri.host}"
            activeBaseUrl = base
            base
        } catch (e: Exception) {
            mainUrl
        }
    }

    private suspend fun getDynamicHeaders(referer: String? = null): Map<String, String> {
        val base = getBaseUrl()
        val map = mutableMapOf(
            "User-Agent" to lastValidUserAgent,
            "Origin" to base,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        map["Referer"] = referer ?: "$base/"
        return map
    }

    override val mainPage = mainPageOf(
        "/movies" to "أفلام أجنبي",
        "/asian-movies" to "أفلام آسيوي",
        "/series" to "جميع المسلسلات",
        "/recent_series" to "أحدث المسلسلات",
        "/episodes" to "احدث الحلقات",
        "/asian-episodes" to "أحدث الحلقات الآسيوية",
        "/recent_asian" to "المضاف حديثا آسيوي",
        "/asian-series" to "جميع المسلسلات الآسيوية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = getBaseUrl()
        val url = "$base${request.data}" + (if (page > 1) "/page/$page" else "")
        val document = app.get(url, headers = getDynamicHeaders()).document
        
        val selector = if (document.selectFirst("div.post-listing") != null) {
            "div.post-listing div.postDiv"
        } else {
            "div.postDiv"
        }

        val home = document.select(selector).mapNotNull {
            it.toSearchResult(base)
        }

        val hasNext = document.select("a[href*='/page/${page + 1}']").isNotEmpty() || 
                      document.select("ul.pagination a, .pagination a, .page-link").any { it.text().contains("التالي") || it.text().contains("Next") }

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val finalHref = if (href.startsWith("http")) href else "$baseUrl$href"
        
        val title = anchor.selectFirst("div.h1, .h1, h1, .post-title, h4, h5")?.text()?.trim() ?: "No Title"
        
        val posterElement = this.selectFirst("div.imgdiv-class img, a > img.img-fluid, img.poster")
        val posterUrl = posterElement?.attr("data-src")?.ifBlank { posterElement.attr("src") }
        val finalPoster = if (posterUrl?.startsWith("http") == false) "$baseUrl$posterUrl" else posterUrl
        
        val isSeries = title.contains("مسلسل") || title.contains("برنامج") ||
                       this.selectFirst("span.quality:contains(حلقة), span.quality:contains(مواسم)") != null
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = finalPoster }
        } else {
            newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = finalPoster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$base/?s=$encodedQuery"
        
        val document = app.get(url, headers = getDynamicHeaders()).document
        
        return document.select("div.postDiv").mapNotNull {
            it.toSearchResult(base)
        }
    }
    
    private fun Element.getMetaInfo(iconClass: String): String? {
        return this.selectFirst("span:has(i.$iconClass)")?.ownText()?.substringAfter(":")?.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = getBaseUrl()
        val document = app.get(url, headers = getDynamicHeaders()).document
        
        val titleElement = document.selectFirst("div.h1.title, h1.title, div.title h1, .singleInfo .title, h1, .post-title")
        val title = titleElement?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim() 
            ?: "No Title"
        
        val posterUrl = document.selectFirst("div.posterImg img, img.poster, .imgdiv-class img, meta[itemprop=image], .singlePage img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("content") }
        }

        val plot = document.selectFirst("div.singleDesc p, div.singleDesc, .story p, .story")?.text()?.trim()

        val tags = document.select("div.col-xl-6:contains(تصنيف) a, .genres a").map { it.text().trim() }
        
        val isTvSeries = document.select("div#seasonList, div.seasonDiv, div#epAll, div.epAll, div#episodes").isNotEmpty()

        if (isTvSeries) {
            val year = document.selectFirst("span:contains(موعد الصدور), span:contains(سنة الإنتاج)")?.text()?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
            var status: ShowStatus? = null
            val statusText = document.selectFirst("span:contains(حالة المسلسل)")?.text() ?: ""
            if (statusText.contains("مستمر")) status = ShowStatus.Ongoing
            else if (statusText.contains("مكتمل")) status = ShowStatus.Completed
            
            val duration = document.getMetaInfo("fa-clock")?.filter { it.isDigit() }?.toIntOrNull()
            val country = document.getMetaInfo("fa-flag")
            val episodeCount = document.getMetaInfo("fa-film")

            var finalPlot = plot ?: ""
            val infoList = mutableListOf<String>()
            episodeCount?.let { infoList.add("<b>الحلقات:</b> $it") }
            country?.let { infoList.add("<b>دولة المسلسل:</b> $it") }
            
            if (infoList.isNotEmpty()) {
                finalPlot += "<br><br>${infoList.joinToString(" | ")}"
            }

            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div#seasonList div.seasonDiv, .seasonDiv")
            val episodeSelector = "div#epAll a, div.epAll a, div#episodes a, div.ep-item a, .epAll a"

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    val onclickAttr = seasonElement.attr("onclick")
                    val seasonLinkRel = Regex("""['"]([^'"]+)['"]""").find(onclickAttr ?: "")?.groupValues?.get(1)
                        ?: seasonElement.selectFirst("a")?.attr("href")
                    
                    if (!seasonLinkRel.isNullOrBlank()) {
                        val absoluteSeasonLink = if (seasonLinkRel.startsWith("http")) seasonLinkRel else "$base$seasonLinkRel"
                        val seasonNum = Regex("""\d+""").find(seasonElement.selectFirst("div.title, .title")?.text() ?: "")?.value?.toIntOrNull() ?: 1
                        
                        val seasonDoc = if (absoluteSeasonLink.substringBefore("?") == url.substringBefore("?")) {
                            document
                        } else {
                            delay(300) 
                            try {
                                app.get(absoluteSeasonLink, headers = getDynamicHeaders()).document
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        seasonDoc?.select(episodeSelector)?.forEach { ep ->
                            val epText = ep.text().trim()
                            val epHref = ep.attr("href")
                            if (epHref.isNotBlank() && !epText.contains("باقي") && !epText.contains("المزيد")) {
                                val finalEpHref = if (epHref.startsWith("http")) epHref else "$base$epHref"
                                episodes.add(
                                    newEpisode(finalEpHref) {
                                        name = epText.ifBlank { "حلقة ${episodes.size + 1}" }
                                        season = seasonNum
                                        episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                                    }
                                )
                            }
                        }
                    }
                }
            } 
            
            if (episodes.isEmpty()) {
                document.select(episodeSelector).forEach { ep ->
                    val epText = ep.text().trim()
                    val epHref = ep.attr("href")
                    if (epHref.isNotBlank() && !epText.contains("باقي") && !epText.contains("المزيد")) {
                        val finalEpHref = if (epHref.startsWith("http")) epHref else "$base$epHref"
                        episodes.add(
                            newEpisode(finalEpHref) {
                                name = epText.ifBlank { "حلقة ${episodes.size + 1}" }
                                season = 1
                                episode = Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()
                            }
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot = finalPlot
                    this.year = year
                    this.tags = tags
                    this.duration = duration
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = finalPlot
                this.year = year
                this.tags = tags
                this.showStatus = status
                this.duration = duration
            }
        } else {
            val year = document.selectFirst("span:contains(سنة الإنتاج) a, span:contains(موعد الصدور)")?.text()?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
            val duration = document.getMetaInfo("fa-clock")?.filter { it.isDigit() }?.toIntOrNull()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
            }
        }
    }

    private fun extractIframeSources(doc: Document): List<String> {
        val results = mutableSetOf<String>()

        val iframeUrl = doc.selectFirst("iframe[name=player_iframe]")?.attr("src") 
            ?: doc.selectFirst("iframe[name=player_iframe]")?.attr("data-src")
        if (!iframeUrl.isNullOrBlank()) results.add(iframeUrl)

        doc.select("ul.tabs-ul li").forEach { serverElement ->
            val serverUrl = serverElement.attr("onclick").substringAfter("href = '").substringBefore("'")
            if (serverUrl.isNotBlank()) {
                val fixedUrl = if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl
                results.add(fixedUrl)
            }
        }

        val blockedKeywords = listOf("google.com", "googlesyndication.com", "googletagmanager.com")
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && blockedKeywords.none { src.contains(it) }) {
                results.add(src)
            }
        }

        return results.toList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String
    ): String? = suspendCancellableCoroutine { cont ->

        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val finalUrl = iframeUrl.replace("&amp;", "&").trim()
        
        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            try {
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (e: Exception) {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
                } catch (_: Exception) {}
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = lastValidUserAgent
                blockNetworkImage = true
            }

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
            } catch (_: Exception) {}

            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .build()

            val foundM3u8 = linkedSetOf<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())
            var finishRunnable: Runnable? = null
            var currentAttempt = 0
            val maxAttempts = 2
            val attemptTimeoutMs = 15_000L
            var attemptTimeoutRunnable: Runnable? = null
            var autoTouchRunnable: Runnable? = null

            fun cleanup() {
                try { attemptTimeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { autoTouchRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) {}
                cleanup()
            }

            fun chooseAndFinish() {
                if (foundM3u8.isEmpty()) { safeFinish(null); return }
                val strict = foundM3u8.firstOrNull {
                    val clean = it.substringBefore("?")
                    clean.endsWith(".m3u8") && (clean.contains("master") || clean.contains("playlist") || clean.contains("index"))
                } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                safeFinish(strict ?: foundM3u8.first())
            }

            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) return
                synchronized(foundM3u8) {
                    if (!foundM3u8.contains(url)) {
                        foundM3u8.add(url)
                        finishRunnable?.let { handler.removeCallbacks(it) }
                        if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) {
                            finishRunnable = Runnable { chooseAndFinish() }
                            handler.postDelayed(finishRunnable!!, 500)
                        } else {
                            if (finishRunnable == null) {
                                finishRunnable = Runnable { chooseAndFinish() }
                                handler.postDelayed(finishRunnable!!, 2000)
                            }
                        }
                    }
                }
            }

            fun startNextAttempt() {
                synchronized(finishLock) { if (finished) return }
                if (currentAttempt >= maxAttempts) {
                    chooseAndFinish()
                    return
                }

                attemptTimeoutRunnable?.let { handler.removeCallbacks(it) }
                attemptTimeoutRunnable = Runnable {
                    synchronized(foundM3u8) {
                        if (foundM3u8.isEmpty()) {
                            currentAttempt++
                            startNextAttempt()
                        } else {
                            chooseAndFinish()
                        }
                    }
                }
                handler.postDelayed(attemptTimeoutRunnable!!, attemptTimeoutMs)

                activity.runOnUiThread {
                    try { webView.loadUrl(finalUrl, mapOf("Referer" to referer)) } catch (_: Exception) {}
                }
            }

            val strategyJs = """
                (function() {
                    Object.defineProperty(navigator, 'userActivation', { get: () => ({ hasBeenActive: true, isActive: true }) });
                    try {
                        let p = typeof window.jwplayer === 'function' ? window.jwplayer("player") : null;
                        if (p && typeof p.play === 'function') { p.setMute(true); p.play(); }
                        var els = document.querySelectorAll('button, a, [onclick], video, .hd_btn');
                        els.forEach(el => { try { el.click(); } catch(e){} });
                    } catch(e) {}
                })();
            """.trimIndent()

            val fastSnifferJs = """
                (function() {
                    try {
                        if (!window.__NET_HOOKED__) {
                            window.__NET_HOOKED__ = true;
                            const _fetch = window.fetch;
                            if (_fetch) {
                                window.fetch = function() {
                                    return _fetch.apply(this, arguments).then(function(resp) {
                                        try {
                                            const u = resp && resp.url ? resp.url : '';
                                            if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                        } catch(e){}
                                        return resp;
                                    });
                                };
                            }
                            const _open = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, u) {
                                this.addEventListener('load', function() {
                                    try {
                                        if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                    } catch(e){}
                                });
                                return _open.apply(this, arguments);
                            };
                        }
                    } catch(err){}
                })();
            """.trimIndent()

            lateinit var sharedWebViewClient: WebViewClient
            sharedWebViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(fastSnifferJs, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(fastSnifferJs, null)

                    autoTouchRunnable?.let { handler.removeCallbacks(it) }
                    autoTouchRunnable = object : Runnable {
                        override fun run() {
                            if (finished) return
                            view?.evaluateJavascript(strategyJs, null)
                            handler.postDelayed(this, 1000)
                        }
                    }
                    handler.postDelayed(autoTouchRunnable!!, 500)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val lower = url.lowercase()

                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".css")) {
                        return super.shouldInterceptRequest(view, request)
                    }

                    if (request.method.equals("GET", ignoreCase = true) && lower.contains(".m3u8")) {
                        handleFoundLink(url)
                        try {
                            val reqBuilder = okhttp3.Request.Builder().url(url)
                                .header("User-Agent", lastValidUserAgent)
                                .header("Referer", referer)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}

                            val response = client.newCall(reqBuilder.build()).execute()
                            if (response.isSuccessful) {
                                val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                                return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                            }
                        } catch (e: Exception) { return null }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            webView.webViewClient = sharedWebViewClient

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    val msg = cm?.message() ?: ""
                    if (msg.startsWith("NET_M3U8::")) {
                        handleFoundLink(msg.substringAfter("::").trim())
                    }
                    return true
                }
            }

            startNextAttempt()
            cont.invokeOnCancellation { handler.post { safeFinish(null) } }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val base = getBaseUrl()
        val response = app.get(data, headers = getDynamicHeaders())
        val document = response.document
        val currentPageUrl = response.url 

        val iframeUrls = extractIframeSources(document)

        if (iframeUrls.isEmpty()) {
            return false
        }

        var foundLink = false

        iframeUrls.distinct().forEach { iframeUrl ->
            if (foundLink) return@forEach 

            val finalIframeUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$base$iframeUrl"
            val m3u8 = resolveWithWebView(finalIframeUrl, currentPageUrl)

            if (!m3u8.isNullOrBlank()) {
                foundLink = true
                val playerOrigin = try { "https://${java.net.URI(finalIframeUrl).host}" } catch(e:Exception){ base }

                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8,
                    referer = finalIframeUrl,
                    headers = mapOf(
                        "Origin" to playerOrigin,
                        "User-Agent" to lastValidUserAgent,
                        "Referer" to finalIframeUrl
                    )
                ).forEach(callback)
            }
        }

        return foundLink
    }
}
