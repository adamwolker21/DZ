
version = 1

cloudstream {
    language = "ar" // اللغة العربية
    description = "إضافة لمشاهدة الأفلام والمسلسلات من موقع فاصل إعلاني"
    authors = listOf("YourName") // يمكنك وضع اسمك هنا

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // 1 يعني أن الإضافة تعمل بشكل جيد
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    // يمكنك إنشاء أيقونة ورفعها على موقع مثل imgur.com ووضع الرابط هنا
    iconUrl = "https://i.imgur.com/example.png"
}
