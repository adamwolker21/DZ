package com.egydead

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

// This is the new entry point for your plugin.
// CloudStream scans for this annotation to discover the plugin.
@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    // This function is called when the app starts.
    // Its job is to register your provider class.
    override fun load(context: Context) {
        registerMainAPI(EgyDead())
    }
}
