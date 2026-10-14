plugins {
    // 版本由根 build.gradle.kts 的插件别名统一提供。
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * 插件契约（plugin-api）：插件与宿主之间的唯一词汇表。
 *
 * 插件（feature 模块）只许依赖本模块 + :core:designsystem + :core:common；
 * 不许直接碰 app 内部实现、各 data 域、会话与凭据。
 * 本模块是叶子：不依赖任何业务模块。
 */
android {
    namespace = "com.ahu.ahutong.core.plugin"
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
    // 契约里出现 OkHttpClient 类型（HTTP 端口），只引 API 不构造（R8 规则：构造只在 :core:network）
    api(libs.okhttp)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.ui)
}
