// TubeArchivist's API and models in plain Kotlin, shared by the phone app and the desktop app
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
}
