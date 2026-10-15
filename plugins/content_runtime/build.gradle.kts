plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ahu.ahutong.runtime.content"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.component.content_runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources { additionalParameters += listOf("--package-id", "0x73", "--allow-reserved-package-id") }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    compileOnly(project(":runtime:shared"))
    compileOnly(project(":core:host-api"))
    implementation(project(":runtime:content"))
}

apply(from = rootProject.file("gradle/ahup.gradle.kts"))

apply(from = rootProject.file("gradle/shared-runtime-ownership.gradle.kts"))
