// استيراد الأدوات اللازمة من CloudStream
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.quickJs
import kotlinx.coroutines.runBlocking

fun extractHls2LinkFromPackedJs(packedJs: String): String? {
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
        val pattern = """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)\)""".toRegex()
        val matchResult = pattern.find(packedJs) ?: run {
            println("CS3 QuickJS: Pattern not found in packed JS")
            return null
        }

        val p = matchResult.groupValues[1]
        val a = matchResult.groupValues[2]
        val c = matchResult.groupValues[3]
        val k = matchResult.groupValues[4]
        
        val fullJsCode = """
            $deobfuscatorJs
            deobfuscate('$p', $a, $c, '$k'); 
        """.trimIndent()

        // ====> هنا كان التصحيح <====
        val deobfuscatedCode = runBlocking {
             app.quickJs(fullJsCode)
        }
        
        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)

        return linkMatch?.groupValues?.get(1)

    } catch (e: Exception) {
        println("CS3 QuickJS Error: ${e.message}")
        e.printStackTrace()
        return null
    }
}
