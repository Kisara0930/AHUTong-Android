import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// This APK is a linking intermediate, never installed as a standalone app.
// AHUP owns the feature classes and resources; the host owns shared runtime APIs.
android {
    namespace = "com.ahu.ahutong.plugin.campuscard"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.campuscard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x86")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("native/build/campus_card/lib"))
    // Rust release artifacts are already stripped and audited; retain their exact digest.
    packaging.jniLibs.keepDebugSymbols += "**/libahup_*.so"
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "11" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.miuix.android)
    compileOnly(libs.monet)
    compileOnly(libs.kyant0.capsule)
    compileOnly(libs.kyant0.backdrop)
    compileOnly(libs.gson)
    compileOnly("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("com.google.zxing:core:3.5.3")
    compileOnly(libs.retrofit)
    compileOnly(project(":core:auth"))
    compileOnly(project(":core:storage"))
    compileOnly(project(":data:personalization"))
    compileOnly("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    compileOnly("androidx.navigation:navigation-compose:2.9.5")
    compileOnly(project(":core:plugin-api"))
    compileOnly(project(":core:designsystem"))
    compileOnly(project(":core:common"))
    compileOnly(project(":core:network"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.ui)
    compileOnly(libs.androidx.foundation)
    compileOnly(libs.androidx.material.icons.extended)
    compileOnly(libs.coil.compose)
    compileOnly("androidx.compose.material3:material3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.register<Exec>("packageAhup") {
    group = "ahup"
    description = "Package the independently linked and signed campus card plugin"
    dependsOn("assembleRelease")
    doFirst {
        val rebuild = "先在仓库根目录运行 python native/build_domains.py --domains campus_card，再重新打包"
        val native = rootProject.file("native/build/campus_card/lib/arm64-v8a/libahup_campus_card.so")
        val index = rootProject.file("native/build/artifacts.json")
        check(native.isFile && native.length() > 0 && index.isFile) { "校园卡原生库或构建索引缺失；$rebuild" }
        val metadata = JsonSlurper().parse(index) as Map<*, *>
        val record = (metadata["artifacts"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.singleOrNull {
            it["domain"] == "campus_card" && it["abi"] == "arm64-v8a"
        }
        check(record != null) { "校园卡原生库没有唯一构建记录；$rebuild" }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = digest(native.readBytes())
        check(record["sha256"] == expected && (record["size"] as? Number)?.toLong() == native.length()) {
            "校园卡原生库与已验证构建记录不符；$rebuild"
        }
        val apk = layout.buildDirectory.file("outputs/apk/release/campus_card-release-unsigned.apk").get().asFile
        ZipFile(apk).use { archive ->
            val entry = archive.getEntry("lib/arm64-v8a/libahup_campus_card.so")
            check(entry != null && archive.getInputStream(entry).use { digest(it.readBytes()) } == expected) {
                "校园卡链接 APK 缺少对应的原生库或字节已改变；$rebuild"
            }
        }
    }
    commandLine(
        providers.gradleProperty("ahupPython")
            .orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/package_ahup.py"),
        projectDir.absolutePath,
        layout.buildDirectory.file("outputs/apk/release/campus_card-release-unsigned.apk").get().asFile.absolutePath,
        rootProject.file("dist/ahup/campus_card.ahup").absolutePath,
        "--id", "campus_card", "--title", "校园卡与账单", "--summary", "校园卡与账单",
        "--version", "1.0.0", "--version-code", "1", "--author", "AHUTong",
        "--entry", "com.ahu.ahutong.plugin.campuscard.CampusCardPlugin",
        "--capabilities", "NETWORK,PLUGIN_STORAGE",
        "--dependency", "account:1",
        "--home-card",
        "--forbid-class-prefix", "com.ahu.ahutong.core.plugin.",
        "--forbid-class-prefix", "androidx.compose.",
        "--forbid-class-prefix", "kotlin.",
        "--java", File(System.getProperty("java.home"),
            "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        ).absolutePath
    )
}

// Kotlin's Gradle plugin otherwise bundles its default stdlib in every plugin.
configurations.configureEach {
    // The JVM test process supplies the SDK that the installed host provides at runtime.
    if (name.endsWith("UnitTestCompileClasspath") || name.endsWith("UnitTestRuntimeClasspath")) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test", ignoreCase = true)) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

// Migrated protocol/state-machine regressions run without making real payments.
dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation("androidx.lifecycle:lifecycle-livedata-core-ktx:2.9.4")
    testImplementation(libs.gson)
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
    testImplementation(libs.converter.gson)
    testImplementation(project(":core:common"))
    testImplementation(project(":core:auth"))
    testImplementation(project(":core:storage"))
    testImplementation(project(":data:personalization"))
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// The Compose compiler also validates the classpath of JVM test compilations.
dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
}
