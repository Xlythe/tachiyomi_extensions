plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "eu.kanade.tachiyomi.multisrc.mangabox"

    sourceSets {
        named("main") {
            java.srcDirs("src")
            res.srcDirs("res")
        }
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.bundles.common)
}
