plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * 校园圈子插件（只读）：接入 zxs-bbs 校园 BBS 的公开只读接口。
 *
 * 契约示范模块：只依赖 plugin-api + designsystem + common + network，
 * 不碰 app 内部、不碰任何会话与凭据。只读流全程零鉴权。
 */
android {
    namespace = "com.ahu.ahutong.feature.circle"
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
    implementation(project(":core:plugin-api"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(project(":core:icons"))
    implementation(libs.coil.compose)
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(kotlin("test-junit"))
}


/*
 * .ahup v3 打包交由独立 APK 链接模块，确保 R 类与资源表一致。
 * 运行：gradlew :feature:circle:packageAhup
 * AHUP_SIGNING_CONFIG 指向本机签名配置；缺失时拒绝生成 release 包。
 */
tasks.register("packageAhup") {
    group = "ahup"
    description = "打包运行期插件（.ahup）"
    dependsOn(":plugins:campus_circle:packageAhup")
}
