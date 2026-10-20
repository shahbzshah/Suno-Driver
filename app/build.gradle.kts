plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.megah.sunopoc"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.megah.sunopoc"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
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
    // Framework-only PoC: no AndroidX dependencies. The Kotlin Android plugin
    // brings in kotlin-stdlib automatically; WebView/Views/org.json are all
    // part of the Android framework.
}
