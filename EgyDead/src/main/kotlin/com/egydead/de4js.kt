// السطر التالي معطل عن قصد، لا تقم بتفعيله
// import com.lagradost.cloudstream3.utils.unpacker.unpackeval

fun extractHls2LinkFromHtml(htmlContent: String): String? {
    try {
        val packedJsRegex = """(eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\))""".toRegex()
        val packedJsScript = packedJsRegex.find(htmlContent)?.value ?: return null

        // سيظهر خطأ باللون الأحمر تحت الكلمة التالية، وهذا هو المطلوب
        val deobfuscatedCode = unpackeval(packedJsScript)

        val linkRegex = """"hls2"\s*:\s*"([^"]+)"""".toRegex()
        val linkMatch = linkRegex.find(deobfuscatedCode)
        return linkMatch?.groupValues?.get(1)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
