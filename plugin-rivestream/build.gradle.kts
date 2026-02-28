plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.omnistream.plugin.rivestream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omnistream.plugin.rivestream"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // plugin-api: provided by host app at runtime — compileOnly keeps the .omni file tiny
    compileOnly(project(":plugin-api"))

    // These are bundled in the host app — compileOnly only
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}

// Package the release APK as a .omni file in the project's dist/ folder
tasks.register("packagePlugin") {
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/${project.name}-release-unsigned.apk").get().asFile
        val dist = rootProject.layout.projectDirectory.dir("dist").asFile
        dist.mkdirs()
        val omni = File(dist, "${project.name.removePrefix("plugin-")}.omni")
        apk.copyTo(omni, overwrite = true)
        println("Packaged: ${omni.absolutePath}")
    }
}
