package com.egydead

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

// A data class to hold both episodes and server links
data class WatchPageData(val episodes: List<Episode>, val serverLinks: List<String>)

object EgyDeadUtils {
    
    suspend fun getWatchPageData(url: String): WatchPageData? {
        try {
            val initialResponse = app.get(url)
            val document = initialResponse.document

            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
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
                val watchPageDoc = app.post(url, headers = headers, data = data, cookies = cookies).document
                return parseWatchPage(watchPageDoc)
            }
            return parseWatchPage(document)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun parseWatchPage(document: Document): WatchPageData {
        val episodes = mutableListOf<Episode>()
        
        document.select("div.EpsList li a").forEach { element ->
            val href = element.attr("href")
            val titleAttr = element.attr("title")
            val epNum = titleAttr.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()
            
            if (epNum != null) {
                val episode = Episode().apply {
                    this.name = element.text().trim()
                    this.episode = epNum
                    this.season = 1
                    this.data = href
                }
                episodes.add(episode)
            }
        }
        
        val serverLinks = document.select("div.servers-list iframe").map { it.attr("src") }
        return WatchPageData(episodes, serverLinks)
    }
}
