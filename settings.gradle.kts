enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "findroid"

// -PdesktopOnly leaves out the Android modules, for building the desktop app on its own
if (!providers.gradleProperty("desktopOnly").isPresent) {
    include(":app:phone")
    include(":app:tv")
    include(":core")
    include(":data")
    include(":player:core")
    include(":player:local")
    include(":setup")
    include(":modes:film")
    include(":settings")
}

include(":tubearchivist")

include(":desktop")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
