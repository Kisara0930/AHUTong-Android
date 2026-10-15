plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ahu.ahutong.runtime.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true; buildConfig = false }
    sourceSets {
        getByName("test").java.srcDir(rootProject.file("app/src/test/java/com/ahu/ahutong/ui/plugin"))
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    compileOnly(project(":core:host-api"))
    api("androidx.appcompat:appcompat:1.7.1")
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:auth"))
    api(project(":core:storage"))
    api(project(":core:network"))
    api(project(":core:designsystem"))
    api(project(":core:plugin-api"))
    // Stable domain vocabulary and ports only; implementations belong to plugins.
    api(project(":data:personalization"))
    api(project(":data:schedule"))
    api(project(":data:grade"))
    api(project(":data:update"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.foundation)
    api("androidx.compose.runtime:runtime-livedata")
    api(project(":core:icons"))
    api(libs.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.navigation.compose)
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.work.runtime.ktx)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.coil)
    api(libs.coil.compose)
    api(libs.miuix.android)
    api(libs.monet)
    api(libs.kyant0.backdrop)
    api(libs.kyant0.capsule)
    api(libs.gson)
    api(libs.jsoup)
    api(libs.retrofit)
    api(libs.converter.gson)
    api(libs.logging.interceptor)
    api(libs.persistentcookiejar)
    api(libs.mmkv.static)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlin.stdlib)
    api("javax.inject:javax.inject:1")
}
dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:host-api"))
}
