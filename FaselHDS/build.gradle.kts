// الآن هذا الطلب سينجح لأن صندوق الأدوات موجود
dependencies {
    implementation(project(":cloudstream3-utils"))
}

version = 13 // رفعنا الإصدار

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
