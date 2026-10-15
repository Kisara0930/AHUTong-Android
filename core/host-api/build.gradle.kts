plugins { id("com.android.library") }
android {
    namespace = "com.ahu.ahutong.hostapi"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
