import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException

fun extractHls2LinkFromPackedJs(packedJs: String): String? {
    // هذا هو كود فك التشفير الأصلي مأخوذ من JavaScript
    // سنقوم بتعريفه كدالة داخل محرك الجافاسكريبت
    val deobfuscatorJs = """
        function deobfuscate(p, a, c, k) {
            while (c--) {
                if (k[c]) {
                    p = p.replace(new RegExp('\\b' + c.toString(a) + '\\b', 'g'), k[c]);
                }
            }
            return p;
        }
    """.trimIndent()

    try {
        // الخطوة 1: استخراج المعطيات (p, a, c, k) من السكريبت المشفر باستخدام Regex
        // هذا النمط يبحث عن الدالة والمحتويات التي بداخلها
        val pattern = """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)\)""".toRegex()
        val matchResult = pattern.find(packedJs) ?: return null // إذا لم يجد النمط، يعود بقيمة فارغة

        val p = matchResult.groupValues[1]
        val a = matchResult.groupValues[2].toInt()
        val c = matchResult.groupValues[3].toInt()
        val k = matchResult.groupValues[4].split("|").toTypedArray()

        // الخطوة 2: إعداد محرك JavaScript وتنفيذ عملية فك التشفير
        Context.create("js").use { context ->
            // قم بتعريف دالة deobfuscate داخل المحرك
            context.eval("js", deobfuscatorJs)
            
            // احصل على الدالة التي عرفناها
            val deobfuscateFunction = context.getBindings("js").getMember("deobfuscate")
            
            // قم بتنفيذ الدالة وتمرير المعطيات لها
            val deobfuscatedCode = deobfuscateFunction.execute(p, a, c, k).asString()

            // الخطوة 3: الآن ابحث عن الرابط داخل الكود المفكوك باستخدام Regex
            val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
            val linkMatch = linkRegex.find(deobfuscatedCode)

            return linkMatch?.groupValues?.get(1) // استخراج الرابط وعرضه
        }

    } catch (e: PolyglotException) {
        println("حدث خطأ في محرك JavaScript: ${e.message}")
        return null
    } catch (e: Exception) {
        println("حدث خطأ عام: ${e.message}")
        return null
    }
}

// --- دالة main للتجربة ---
fun main() {
    // ضع هنا السكريبت المشفر الكامل الذي تريد تحليله
    val packedJavaScript = """
    eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('var links={"hls2":"https://example.com/your-target-link/master.m3u8"};',2,2,'links|var'.split('|')))
    """.trimIndent()

    println("جاري محاولة استخراج الرابط...")
    val extractedLink = extractHls2LinkFromPackedJs(packedJavaScript)

    if (extractedLink != null) {
        println("✅ تم استخراج الرابط بنجاح:")
        println(extractedLink)
    } else {
        println("❌ فشلت عملية استخراج الرابط.")
    }
}
