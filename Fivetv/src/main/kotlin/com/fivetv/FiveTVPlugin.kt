package com.fivetv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FiveTVPlugin: Plugin() {
    override fun load(context: Context) {
        // تسجيل المزود الأساسي
        registerMainAPI(FiveTVProvider())
        
        // تسجيل المستخرجات المخصصة للسيرفرات
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(StreamHG())
    }
}
