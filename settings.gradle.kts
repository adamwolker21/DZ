// هذا الملف يخبر المشروع بكل المكونات الموجودة فيه
// لقد أضفنا سطرين مهمين في الأسفل

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "CSX"
include(":Bollyflix")
include(":Extractors")
include(":FaselHDS") // ✨ التأكد من وجود إضافتك في الخريطة
include(":cloudstream3-utils") // ✨✨✨ هذا هو السطر الحاسم الذي يضيف "صندوق الأدوات" للمشروع كله ✨✨✨
