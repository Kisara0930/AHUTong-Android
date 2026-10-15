plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ahu.ahutong.runtime.content"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    compileOnly(project(":runtime:shared"))
    compileOnly(project(":core:host-api"))
    api(libs.markwon.core)
    api(libs.commonmark.tables)
    api(libs.commonmark.strikethrough)
}
