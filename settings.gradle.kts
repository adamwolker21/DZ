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
// FaselHDS فقط، بدون أي إضافات أخرى
include(":FaselHDS")
