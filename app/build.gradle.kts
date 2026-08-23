plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tkjone.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tkjone.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val signKey = System.getenv("SIGN_KEY")
            if (!signKey.isNullOrEmpty()) {
                storeFile = file(signKey)
                storePassword = System.getenv("SIGN_STORE_PASS") ?: ""
                keyAlias = System.getenv("SIGN_ALIAS") ?: ""
                keyPassword = System.getenv("SIGN_KEY_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            val signKey = System.getenv("SIGN_KEY")
            if (!signKey.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // JSON parsing
    implementation("org.json:json:20231013")
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
