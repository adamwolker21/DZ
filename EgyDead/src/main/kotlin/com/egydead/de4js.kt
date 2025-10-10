import com.lagradost.cloudstream3.app // تأكد من وجود هذا الاستيراد

fun extractHls2LinkFromHtml(htmlContent: String): String? {
    return try {
        // الخطوة 1: ابحث عن السكريبت المشفر
        val packedJsRegex = """(eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\))""".toRegex()
        val packedJsScript = packedJsRegex.find(htmlContent)?.value ?: return null

        // الخطوة 2: استخدم دالة فك التشفير الموجودة في كائن app
        // هذه هي الطريقة الأكثر شيوعًا في العديد من النسخ
        val deobfuscatedCode = app.unpackEval(packedJsScript)

        if (deobfuscatedCode.isNullOrBlank()) {
            println("app.unpackEval: فشلت عملية فك التشفير.")
            return null
        }

        // الخطوة 3: ابحث عن الرابط كالمعتاد
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        linkMatch?.groupValues?.get(1)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
