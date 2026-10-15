plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ahu.ahutong"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.component.ui_runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources { additionalParameters += listOf("--package-id", "0x71", "--allow-reserved-package-id") }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("test").java.srcDir(rootProject.file("host/src/test/java"))
        getByName("main") {
            java.srcDirs(rootProject.file("host/src/main/java"), rootProject.file("app/src/main/java/com/ahu/ahutong/ui/shell"),
                rootProject.file("app/src/main/java/com/ahu/ahutong/platform"))
            res.srcDir(rootProject.file("host/src/main/res"))
            assets.srcDir(rootProject.file("host/src/main/assets/licenses"))
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    compileOnly(project(":core:host-api"))
    compileOnly(project(":runtime:shared"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":runtime:shared"))
    testImplementation(project(":core:host-api"))
}

apply(from = rootProject.file("gradle/ahup.gradle.kts"))

apply(from = rootProject.file("gradle/shared-runtime-ownership.gradle.kts"))
