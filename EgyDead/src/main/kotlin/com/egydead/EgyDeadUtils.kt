package com.egydead

import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

object EgyDeadUtils {
    
    suspend fun getPageWithEpisodes(url: String): Document? {
        try {
            val initialResponse = app.get(url)
            var document = initialResponse.document

            // Check if we need to "click" the button
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                // Add navigation headers to simulate a real form submission
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to "https://tv6.egydead.live",
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-user" to "?1"
                )
                val data = mapOf("View" to "1")
                document = app.post(url, headers = headers, data = data, cookies = cookies).document
            }
            return document
        } catch (e: Exception) {
            return null
        }
    }
}
