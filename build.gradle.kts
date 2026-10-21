plugins {
    kotlin("jvm") version "2.2.0"
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
        classpath("com.google.gms:google-services:4.3.3")
    }
}

// Repositori dependency sudah dideklarasikan di settings.gradle.kts
// (FAIL_ON_PROJECT_REPOS), jadi tidak boleh diulang di sini.

kotlin {
    sourceSets["main"].kotlin.srcDir("src/main/java")
    sourceSets["test"].kotlin.srcDir("src/test/java")
}
