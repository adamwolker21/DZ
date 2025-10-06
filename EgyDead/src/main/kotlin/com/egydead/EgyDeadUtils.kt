package com.egydead

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

data class WatchPageData(val episodes: List<Episode>, val serverLinks: List<String>)

object EgyDeadUtils {
    
    suspend fun getWatchPageData(url: String): WatchPageData? {
        return try {
            val response = app.get(url)
            val doc = response.document
            
            if (doc.selectFirst("div.watchNow form") != null) {
                val watchDoc = app.post(
                    url, 
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to url
                    ),
                    data = mapOf("View" to "1"),
                    cookies = response.cookies
                ).document
                parseWatchPage(watchDoc)
            } else {
                parseWatchPage(doc)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseWatchPage(document: Document): WatchPageData {
        val episodes = document.select("div.EpsList li a").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.attr("title")
            val epNum = title.substringAfter("الحلقة").trim().substringBefore(" ").toIntOrNull()
            
            epNum?.let {
                Episode(
                    data = href,
                    name = element.text().trim(),
                    episode = it,
                    season = 1
                )
            }
        }
        
        val serverLinks = document.select("div.servers-list iframe").map { it.attr("src") }
        return WatchPageData(episodes, serverLinks)
    }
}
