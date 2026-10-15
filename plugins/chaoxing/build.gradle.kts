plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// This APK is a linking intermediate, never installed as a standalone app.
// AHUP owns the feature classes and resources; the host owns shared runtime APIs.
android {
    namespace = "com.ahu.ahutong.plugin.chaoxing"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.chaoxing"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x8A")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
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
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.miuix.android)
    compileOnly(libs.monet)
    compileOnly(libs.kyant0.capsule)
    compileOnly(libs.kyant0.backdrop)
    compileOnly(libs.gson)
    compileOnly(libs.kotlinx.serialization.json)
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
    // The JVM test process supplies the SDK that the installed host provides at runtime.
    if (name.endsWith("UnitTestCompileClasspath") || name.endsWith("UnitTestRuntimeClasspath")) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test", ignoreCase = true)) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

// Retain domain regression tests alongside the implementation after migration.
dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.gson)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)
}

// The Compose compiler also validates the classpath of JVM test compilations.
dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
}
