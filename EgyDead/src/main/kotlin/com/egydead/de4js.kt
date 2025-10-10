import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.getAndUnpack

// ملاحظة: قد تحتاج إلى إضافة هذه الدالة المساعدة في ملفك إذا لم تكن موجودة
// وهي تقوم بإزالة علامات الاقتباس من النص الذي يعود من JavaScript
private fun String.unquote(): String {
    return this.removeSurrounding("\"")
}

fun extractHls2LinkFromHtml(htmlContent: String): String? {
    return try {
        // الخطوة 1: ابحث عن السكريبت المشفر
        val packedJsRegex = """(eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\))""".toRegex()
        val packedJsScript = packedJsRegex.find(htmlContent)?.value ?: return null

        // الخطوة 2: استخرج المعطيات (p, a, c, k) من نص السكريبت
        val paramsRegex = """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)\)""".toRegex()
        val matchResult = paramsRegex.find(packedJsScript) ?: return null

        val p = matchResult.groupValues[1]
        val a = matchResult.groupValues[2]
        val c = matchResult.groupValues[3]
        val k = matchResult.groupValues[4]
        
        // الخطوة 3: قم ببناء كود JavaScript الذي سيتم تنفيذه
        val jsToExecute = """
            // دالة فك التشفير الأصلية
            function deobfuscate(p, a, c, k) {
                k = k.split('|');
                while (c--) {
                    if (k[c]) {
                        p = p.replace(new RegExp('\\b' + c.toString(a) + '\\b', 'g'), k[c]);
                    }
                }
                return p;
            }
            
            // استدعاء الدالة بالمعطيات التي استخرجناها
            deobfuscate('$p', $a, $c, '$k'); 
        """.trimIndent()

        // الخطوة 4: نفذ الكود باستخدام app.eval واحصل على النتيجة
        val deobfuscatedCode = app.eval(jsToExecute).unquote()

        // الخطوة 5: ابحث عن الرابط في النتيجة
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        linkMatch?.groupValues?.get(1)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
