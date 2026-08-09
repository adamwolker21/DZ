package com.krmzi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KrmziPlugin : Plugin() {
    override fun load(context: Context) {
        // تسجيل المزود الأساسي
        registerMainAPI(KrmziProvider())
        
        // تسجيل المستخرجات لجميع السيرفرات
        registerExtractorAPI(ArabHdExtractor())
        registerExtractorAPI(TurkExtractor())
        registerExtractorAPI(RedHdExtractor())
    }
}
