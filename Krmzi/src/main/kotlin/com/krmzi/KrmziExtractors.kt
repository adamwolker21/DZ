package com.krmzi

import com.lagradost.cloudstream3.utils.ExtractorApi

// حل الخطأ 1 و 2: تنظيف الملف وإزالة الدوال غير المتوافقة حالياً. 
// سنتركه فارغاً وبشكل صحيح لكي لا يسبب أخطاء في البناء.
class KrmziExtractor : ExtractorApi() {
    override val name = "Krmzi VIP"
    override val mainUrl = "https://krmzi.org"
    override val requiresReferer = false
}
