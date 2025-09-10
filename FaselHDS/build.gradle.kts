// ✨ تم التعديل هنا: إضافة سطر implementation ✨
// هذا السطر يخبر المشروع بأننا نحتاج "صندوق الأدوات" الذي يحتوي على CloudflareKiller
dependencies {
    implementation(project(":cloudstream3-utils"))
}

version = 7 // تم رفع الإصدار

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
