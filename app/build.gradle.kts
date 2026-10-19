plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

android {
    namespace = "com.tkjone.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "aokaze.jirankun.dev"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.2"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    // Chrome Custom Tabs — buka halaman upload di browser tab dalam-app
    implementation("androidx.browser:browser:1.8.0")
    // JSON parsing
    implementation("org.json:json:20231013")
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Image loading with sampled decoding and animated GIF support
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
}
