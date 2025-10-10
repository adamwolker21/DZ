// المسار الصحيح للدالة المطلوبة
import com.lagradost.cloudstream3.utils.unpacker.unpackeval

// لا نحتاج لكائن app هنا
// import com.lagradost.cloudstream3.app

fun extractHls2LinkFromHtml(htmlContent: String): String? {
    try {
        // الخطوة 1: ابحث عن السكريبت المشفر كاملاً
        // تم تحسين النمط ليكون أكثر دقة
        val packedJsRegex = """(eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\))""".toRegex()
        val packedJsScript = packedJsRegex.find(htmlContent)?.value ?: run {
            println("unpackeval: لم يتم العثور على سكريبت eval.")
            return null
        }

        // الخطوة 2: استخدم دالة unpackeval مباشرة
        // هذه هي الدالة الصحيحة والمخصصة لهذه المهمة
        val deobfuscatedCode = unpackeval(packedJsScript)

        if (deobfuscatedCode.isBlank()) {
            println("unpackeval: فشلت عملية فك التشفير.")
            return null
        }

        // الخطوة 3: ابحث عن الرابط في الكود المفكوك
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        // إرجاع الرابط الذي تم العثور عليه
        return linkMatch?.groupValues?.get(1)

    } catch (e: Exception) {
        println("unpackeval Error: ${e.message}")
        e.printStackTrace()
        return null
    }
}
