plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ahu.ahutong.core.icons"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    api("androidx.compose.material:material-icons-core:1.7.8")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
}
