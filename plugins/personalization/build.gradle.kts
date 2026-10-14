plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// This APK is a linking intermediate, never installed as a standalone app.
// AHUP owns the feature classes and resources; the host owns shared runtime APIs.
android {
    namespace = "com.ahu.ahutong.plugin.personalization"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.personalization"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x93")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true; buildConfig = true }
    kotlinOptions { jvmTarget = "11" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    compileOnly(project(":core:storage"))
    compileOnly(project(":core:auth"))
    compileOnly(libs.androidx.room.runtime)
    compileOnly(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.gson)
    testImplementation(project(":data:personalization"))
    testImplementation(libs.androidx.room.runtime)
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
    compileOnly(libs.androidx.material.icons.extended)
    compileOnly(libs.coil.compose)
    compileOnly("androidx.compose.material3:material3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.register<Exec>("packageAhup") {
    group = "ahup"
    description = "Package the independently linked and signed personalization plugin"
    dependsOn("assembleRelease")
    commandLine(
        providers.gradleProperty("ahupPython")
            .orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/package_ahup.py"),
        projectDir.absolutePath,
        layout.buildDirectory.file("outputs/apk/release/personalization-release-unsigned.apk").get().asFile.absolutePath,
        rootProject.file("dist/ahup/personalization.ahup").absolutePath,
        "--id", "personalization", "--title", "智能助手", "--summary", "智能助手",
        "--version", "1.0.0", "--version-code", "1", "--author", "AHUTong",
        "--entry", "com.ahu.ahutong.plugin.personalization.PersonalizationPlugin",
        "--capabilities", "NETWORK,PLUGIN_STORAGE",
        "--home-card",
        "--auto-start",
        "--dependency", "account:1",
        "--forbid-class-prefix", "com.ahu.ahutong.core.plugin.",
        "--forbid-class-prefix", "androidx.compose.",
        "--forbid-class-prefix", "kotlin.",
        "--java", File(System.getProperty("java.home"),
            "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        ).absolutePath
    )
}

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
