plugins {
    // 版本由根 build.gradle.kts 的插件别名统一提供。
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

/**
 * 仓库索引 feature：浏览、下载与阅读学习资料。
 *
 * 界面只依赖 ViewModel，ViewModel 只依赖接口（RepositoryIndex + RepositoryFileAccess），
 * 因此它可以在 JVM 单测里被 fake 驱动。实现分别留在 :data:repository-index 与 :app。
 */
android {
    namespace = "com.ahu.ahutong.feature.repositoryindex"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:personalization"))
    implementation(project(":data:repository-index"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.material3)
    implementation(project(":core:icons"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.miuix.android)
    implementation(libs.monet)
    implementation(libs.kyant0.backdrop)
    implementation(libs.kyant0.capsule)
    implementation(libs.markwon.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // 删除策略的测试与被测代码同模块：它是 internal，:app 看不到（跨模块 internal 不可见）。
    testImplementation(kotlin("test-junit"))
    // RepositoryViewModel 的契约测试用假端口驱动，不碰设备、不联网。
    testImplementation(libs.kotlinx.coroutines.test)
}
