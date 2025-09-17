package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.CloudstreamPlugin
import com.lagradost.cloudstream3.Plugin

class EgyDeadPlugin : CloudstreamPlugin() {
    override fun load(context: Context): Plugin {
        return EgyDeadProvider()
    }
}
