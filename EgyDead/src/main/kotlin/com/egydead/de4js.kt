// استيراد الأدوات اللازمة من CloudStream
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.quickJs
import com.lagradost.cloudstream3.utils.Coroutines
import kotlinx.coroutines.runBlocking

// تم تعديل الدالة لتعمل مع بيئة CloudStream
fun extractHls2LinkFromPackedJs(packedJs: String): String? {
    // نفس دالة فك التشفير المكتوبة بـ JavaScript
    val deobfuscatorJs = """
        function deobfuscate(p, a, c, k) {
            k = k.split('|');
            while (c--) {
                if (k[c]) {
                    p = p.replace(new RegExp('\\b' + c.toString(a) + '\\b', 'g'), k[c]);
                }
            }
            return p;
        }
    """.trimIndent()

    try {
        // الخطوة 1: استخراج المعطيات بنفس الطريقة باستخدام Regex
        val pattern = """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)\)""".toRegex()
        val matchResult = pattern.find(packedJs) ?: run {
            println("CS3 QuickJS: Pattern not found in packed JS")
            return null
        }

        val p = matchResult.groupValues[1]
        val a = matchResult.groupValues[2]
        val c = matchResult.groupValues[3]
        val k = matchResult.groupValues[4]
        
        // الخطوة 2: استخدام محرك QuickJS المدمج في CloudStream
        // نقوم بتجميع الكود الذي سيتم تنفيذه
        val fullJsCode = """
            $deobfuscatorJs
            
            // استدعاء الدالة بالمعطيات التي استخرجناها
            deobfuscate('$p', $a, $c, '$k'); 
        """.trimIndent()

        // Coroutines.main { ... } هي الطريقة الصحيحة لتشغيل الكود الذي قد يستغرق بعض الوقت
        // app.quickJs.evaluate() هي الدالة التي تنفذ كود JavaScript
        val deobfuscatedCode = runBlocking {
             app.quickJs(fullJsCode)
        }
        
        // الخطوة 3: البحث عن الرابط في النص المفكوك
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        return linkMatch?.groupValues?.get(1)

    } catch (e: Exception) {
        println("CS3 QuickJS Error: ${e.message}")
        e.printStackTrace()
        return null
    }
}
