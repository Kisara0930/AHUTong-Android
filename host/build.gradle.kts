plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The distributable application. Business implementations are only present in AHUPs.
android {
    namespace = "com.ahu.ahutong"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong"
        minSdk = 26
        targetSdk = 36
        versionCode = 304002
        versionName = "3.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "src/main/java",
                "../app/src/main/java/com/ahu/ahutong/ui/plugin",
                "../app/src/main/java/com/ahu/ahutong/ui/shell",
                "../app/src/main/java/com/ahu/ahutong/platform"
            )
            assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets"))
        }
        getByName("test").java.srcDir("../app/src/test/java/com/ahu/ahutong/ui/plugin")
        getByName("androidTest").java.srcDir("../app/src/androidTest/java/com/ahu/ahutong/ui/plugin")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".pluginpreview"
            versionNameSuffix = "-plugin-preview"
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            // Separately compiled AHUPs and RemoteViews reference the public SDK resource IDs.
            // A shrinker cannot infer these references from the host's own bytecode.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:auth"))
    implementation(project(":core:storage"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:plugin-api"))
    // Stable domain vocabulary and ports only; implementations belong to plugins.
    implementation(project(":data:personalization"))
    implementation(project(":data:schedule"))
    implementation(project(":data:grade"))
    implementation(project(":data:update"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.miuix.android)
    implementation(libs.monet)
    implementation(libs.kyant0.backdrop)
    implementation(libs.kyant0.capsule)
    implementation(libs.gson)
    implementation(libs.jsoup)
    implementation(libs.markwon.core)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
    implementation(libs.persistentcookiejar)
    implementation(libs.mmkv.static)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation("javax.inject:javax.inject:1")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val bootstrapAssets by tasks.registering(Sync::class) {
    dependsOn(":plugins:account:packageAhup", ":plugins:appearance:packageAhup", ":plugins:storage_migration:packageAhup")
    from(rootProject.file("dist/ahup/account.ahup")) { into("bootstrap") }
    from(rootProject.file("dist/ahup/appearance.ahup")) { into("bootstrap") }
    from(rootProject.file("dist/ahup/storage_migration.ahup")) { into("migrations") }
    into(layout.buildDirectory.dir("generated/bootstrapAssets"))
}
tasks.matching { it.name != "bootstrapAssets" && (it.name.endsWith("Assets") || it.name.contains("Lint", ignoreCase = true)) }
    .configureEach { dependsOn(bootstrapAssets) }
