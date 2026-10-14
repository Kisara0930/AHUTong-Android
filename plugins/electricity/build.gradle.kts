plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// This APK is a linking intermediate, never installed as a standalone app.
// AHUP owns the feature classes and resources; the host owns shared runtime APIs.
android {
    namespace = "com.ahu.ahutong.plugin.electricity"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.electricity"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x87")
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
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.miuix.android)
    compileOnly(libs.monet)
    compileOnly(libs.kyant0.capsule)
    compileOnly(libs.kyant0.backdrop)
    compileOnly(libs.gson)
    compileOnly("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    compileOnly(libs.retrofit)
    compileOnly(project(":core:auth"))
    compileOnly(project(":core:storage"))
    compileOnly(project(":data:personalization"))
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
    description = "Package the independently linked and signed campus circle plugin"
    dependsOn("assembleRelease")
    commandLine(
        providers.gradleProperty("ahupPython")
            .orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/package_ahup.py"),
        projectDir.absolutePath,
        layout.buildDirectory.file("outputs/apk/release/electricity-release-unsigned.apk").get().asFile.absolutePath,
        rootProject.file("dist/ahup/electricity.ahup").absolutePath,
        "--id", "electricity", "--title", "电费", "--summary", "电费",
        "--version", "1.0.0", "--version-code", "1", "--author", "AHUTong",
        "--entry", "com.ahu.ahutong.plugin.electricity.ElectricityPlugin",
        "--capabilities", "NETWORK,PLUGIN_STORAGE",
        "--dependency", "account:1",
        "--home-card",
        "--foreground",
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

// Migrated protocol/state-machine regressions run without making real payments.
dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation("androidx.lifecycle:lifecycle-livedata-core-ktx:2.9.4")
    testImplementation(libs.gson)
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
    testImplementation(libs.converter.gson)
    testImplementation(project(":core:common"))
    testImplementation(project(":core:auth"))
    testImplementation(project(":core:storage"))
    testImplementation(project(":data:personalization"))
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// The Compose compiler also validates the classpath of JVM test compilations.
dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
}
