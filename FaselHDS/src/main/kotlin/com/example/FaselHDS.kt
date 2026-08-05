package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FaselHDS: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner.
        // قمنا بتمرير المتغير context هنا لتمكين إضافة المتصفح المخفي من العمل
        registerMainAPI(FaselHDSProvider(context))
    }
}
