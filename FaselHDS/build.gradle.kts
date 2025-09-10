// تم حذف سطر الاعتماديات بالكامل لضمان نجاح البناء
// dependencies { ... }

version = 8 // تم رفع الإصدار

cloudstream {
    language = "ar"
    description = "إضافة لمشاهدة الأفلام والمسلسلات من موقع فاصل إعلاني"
    authors = listOf("adamwolker21")

    status = 1 
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    // الرجاء تحديث هذا الرابط لاحقاً
    iconUrl = "https://i.imgur.com/example.png"
}
