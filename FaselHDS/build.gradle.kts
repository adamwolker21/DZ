// تم العثور على الطريقة الصحيحة لإضافة الاعتماديات لمشروعك
// هذا السطر يخبر المشروع أنه يحتاج إلى حزمة الأدوات المساعدة
apply(from = "$rootDir/build-scripts/helpers.gradle")

version = 9 // تم رفع الإصدار

cloudstream {
    language = "ar"
    description = "إضافة لمشاهدة الأفلام والمسلسلات من موقع فاصل إعلاني"
    authors = listOf("adamwolker21")

    status = 1 
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://i.imgur.com/example.png"
}
