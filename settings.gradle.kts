// هذا الملف يخبر المشروع بكل المكونات الموجودة فيه
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
include(":FaselHDS")
// ✨ الآن هذا السطر سيجد المجلد الذي أنشأناه للتو ✨
include(":cloudstream3-utils")
