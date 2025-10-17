package com.asiatv.one

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AsiatvonePlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added here
        registerMainAPI(AsiatvoneProvider())
        
        // Register custom extractors
        // Assuming AsiaTvPlayer will be defined later
        // registerExtractorAPI(AsiaTvPlayer()) 
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(Earnvids())
    }
}
