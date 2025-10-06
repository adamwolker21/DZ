package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.egydead.extractors.StreamHGExtractor
import com.egydead.extractors.ForafileExtractor

@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    override fun load(context: Context) {
        // 1. Register the main provider for browsing and searching content
        registerMainAPI(EgyDeadProvider())

        // 2. Register the custom extractors to handle specific video hosts
        // This is the crucial step that was missing.
        registerExtractorAPI(StreamHGExtractor())
        registerExtractorAPI(ForafileExtractor())
    }
}
