plugins { id("com.android.application") }
android {
    namespace = "com.ahu.ahutong.bootstrap"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong"
        minSdk = 26
        targetSdk = 36
        versionCode = 304003
        versionName = "3.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/native/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/native/java"))
            res.setSrcDirs(listOf("src/native/res"))
            assets.setSrcDirs(listOf("src/native/assets", "src/main/assets"))
        }
    }
    buildFeatures { buildConfig = false }
    buildTypes {
        debug { applicationIdSuffix = ".pluginpreview"; ndk { abiFilters += "x86_64" } }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild { cmake { path = file("src/native/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
    implementation(project(":core:host-api"))
    implementation(project(":bootstrap-kernel"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
