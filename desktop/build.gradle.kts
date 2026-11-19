import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Tubedroid for Windows: the TubeArchivist pages of the phone app, with mpv playing the videos
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(projects.tubearchivist)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.mp.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jna.platform)
}

// The phone app's icons, so both look the same
tasks.processResources {
    from(rootProject.file("core/src/main/res/drawable")) {
        include("ic_*.xml")
        into("icons")
    }
}

// The version of the installer, from the build number on GitHub
val desktopVersion = providers.gradleProperty("desktopVersion").orElse("1.0.0")

compose.desktop {
    application {
        mainClass = "dev.jdtech.tubedroid.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Tubedroid"
            packageVersion = desktopVersion.get()
            description = "TubeArchivist client"
            vendor = "Tubedroid"
            includeAllModules = true
            // libmpv-2.dll goes in resources/windows-x64, put there by the build on GitHub
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "Tubedroid"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keeps installs of newer versions replacing older ones
                upgradeUuid = "7f6b3c1e-4d2a-4b8e-9c5f-2a1d6e8b9c40"
            }
        }
    }
}

// Draws each page with sample data into PNG files, to check the layout without running the app
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Renders the pages with sample data into build/screens."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.jdtech.tubedroid.desktop.preview.RenderScreensKt"
    val output = layout.buildDirectory.dir("screens")
    args(output.get().asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true")
    javaLauncher = javaToolchains.launcherFor { languageVersion = Versions.JAVA }
}
