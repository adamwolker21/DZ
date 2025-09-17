package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class EgyDeadPlugin : CloudstreamPlugin() {
    override fun load(context: Context): Plugin {
        return Plugin(
            name = "EgyDead",
            version = 1,
            provider = EgyDeadProvider()
        )
    }
}
