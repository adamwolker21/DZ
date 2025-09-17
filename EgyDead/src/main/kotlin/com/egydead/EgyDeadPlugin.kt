package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

class EgyDeadPlugin : CloudstreamPlugin() {
    override fun load(context: Context) {
        registerMainAPI(EgyDeadProvider())
    }
}
