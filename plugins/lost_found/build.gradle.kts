plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// This APK is a linking intermediate, never installed as a standalone app.
// AHUP owns the feature classes and resources; the host owns shared runtime APIs.
android {
    namespace = "com.ahu.ahutong.plugin.lost_found"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.lost_found"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x91")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "11" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(project(":runtime:shared"))
    compileOnly(project(":core:host-api"))
    testImplementation(project(":core:common"))
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    compileOnly(project(":core:auth"))
    compileOnly(project(":core:storage"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.gson)
    testImplementation(libs.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.miuix.android)
    compileOnly(libs.monet)
    compileOnly(libs.kyant0.capsule)
    compileOnly(libs.kyant0.backdrop)
    compileOnly(libs.gson)
    compileOnly(project(":data:personalization"))
    compileOnly(libs.retrofit)
    compileOnly("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    compileOnly("androidx.navigation:navigation-compose:2.9.5")
    compileOnly(project(":core:plugin-api"))
    compileOnly(project(":core:designsystem"))
    compileOnly(project(":core:common"))
    compileOnly(project(":core:network"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.ui)
    compileOnly(libs.androidx.foundation)
    compileOnly(project(":core:icons"))
    compileOnly(libs.coil.compose)
    compileOnly("androidx.compose.material3:material3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

apply(from = rootProject.file("gradle/ahup.gradle.kts"))

// Kotlin's Gradle plugin otherwise bundles its default stdlib in every plugin.
configurations.configureEach {
    if (name == "releaseRuntimeClasspath" || name == "debugRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath") || name.endsWith("UnitTestCompileClasspath")) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
}
