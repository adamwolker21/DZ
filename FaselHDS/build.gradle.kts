// الآن يمكننا أن نطلب الاعتماديات بشكل صحيح
dependencies {
    implementation(project(":cloudstream3-utils"))
}

version = 11

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
