// استيراد الأداة الصحيحة والمخصصة لهذه المهمة
import com.lagradost.cloudstream3.utils.JsUnpacker.jsUnpacker
import com.lagradost.cloudstream3.app

// الدالة الآن أبسط بكثير
// ستحتاج إلى تمرير محتوى الصفحة (HTML) كاملاً لهذه الدالة
fun extractHls2LinkFromHtml(htmlContent: String): String? {
    try {
        // الخطوة 1: ابحث عن السكريبت المشفر داخل محتوى الـ HTML
        val packedJsRegex = """eval\(function\(p,a,c,k,e,d\).*?\)""".toRegex()
        val packedJsScript = packedJsRegex.find(htmlContent)?.value ?: run {
            println("JsUnpacker: لم يتم العثور على سكريبت eval.")
            return null
        }

        // الخطوة 2: استخدم دالة jsUnpacker الجاهزة لفك التشفير
        // هذه الدالة ستقوم بكل العمل تلقائياً
        val deobfuscatedCode = app.jsUnpacker(packedJsScript)

        if (deobfuscatedCode.isBlank()) {
            println("JsUnpacker: فشلت عملية فك التشفير.")
            return null
        }

        // الخطوة 3: ابحث عن الرابط في الكود المفكوك كالمعتاد
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        return linkMatch?.groupValues?.get(1)

    } catch (e: Exception) {
        println("JsUnpacker Error: ${e.message}")
        e.printStackTrace()
        return null
    }
}
