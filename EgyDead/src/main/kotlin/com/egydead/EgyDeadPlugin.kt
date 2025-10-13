package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    override fun load(context: Context) {
        // تسجيل البروفايدر الرئيسي
        registerMainAPI(EgyDeadProvider())

        // ✅  تسجيل جميع المستخرِجات الموجودة في القائمة
        // هذا يضمن أن التطبيق سيتعرف على Earnvids و StreamHG
        extractorList.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
