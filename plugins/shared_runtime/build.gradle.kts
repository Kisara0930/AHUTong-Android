plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ahu.ahutong.runtime.shared"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.component.shared_runtime"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources { additionalParameters += listOf("--package-id", "0x70", "--allow-reserved-package-id") }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes { release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), rootProject.file("build/generated/runtime-abi/shared-runtime.pro"))
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    compileOnly(project(":core:host-api"))
    implementation(project(":runtime:shared"))
}

apply(from = rootProject.file("gradle/ahup.gradle.kts"))
apply(from = rootProject.file("gradle/runtime-shrinking.gradle.kts"))
