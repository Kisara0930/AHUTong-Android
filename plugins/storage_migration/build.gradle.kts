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
    namespace = "com.ahu.ahutong.plugin.storage_migration"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahu.ahutong.plugin.storage_migration"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    androidResources {
        // Stable allocation in the official plugin catalog; host keeps 0x7f.
        additionalParameters += listOf("--package-id", "0x95")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("native/build/storage/lib"))
    // Cargo already strips and verifies this ELF; preserve its audited bytes.
    packaging { jniLibs.keepDebugSymbols += "**/libahup_*.so" }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "11" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    compileOnly(project(":core:auth"))
    compileOnly(project(":core:storage"))
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.miuix.android)
    compileOnly(libs.monet)
    compileOnly(libs.kyant0.capsule)
    compileOnly(libs.kyant0.backdrop)
    compileOnly(libs.gson)
    compileOnly(project(":data:personalization"))
    compileOnly(libs.retrofit)
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
    description = "Package the independently linked and signed storage_migration plugin"
    dependsOn("assembleRelease")
    doFirst {
        val nativeLibrary = rootProject.file("native/build/storage/lib/arm64-v8a/libahup_storage.so")
        val indexFile = rootProject.file("native/build/artifacts.json")
        val repair = "Run python native/build_domains.py --domains storage before packaging."
        check(nativeLibrary.isFile && nativeLibrary.length() > 0 && indexFile.isFile) {
            "Verified storage migration native library is missing. $repair"
        }
        val index = groovy.json.JsonSlurper().parse(indexFile) as Map<*, *>
        val record = (index["artifacts"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.singleOrNull { it["domain"] == "storage" && it["abi"] == "arm64-v8a" }
            ?: error("Storage native artifact index is missing/ambiguous. $repair")
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = record["sha256"]?.toString()
        check((record["size"] as? Number)?.toLong() == nativeLibrary.length() && digest(nativeLibrary.readBytes()) == expected) {
            "Storage native artifact does not match its validated index. $repair"
        }
        val apk = layout.buildDirectory.file("outputs/apk/release/storage_migration-release-unsigned.apk").get().asFile
        ZipFile(apk).use { archive ->
            val entry = archive.getEntry("lib/arm64-v8a/libahup_storage.so")
                ?: error("Storage migration APK omitted its required native library. $repair")
            check(entry.size == nativeLibrary.length() && digest(archive.getInputStream(entry).use { it.readBytes() }) == expected) {
                "Storage migration APK changed its verified native library; preserve keepDebugSymbols and rebuild."
            }
        }
    }
    commandLine(
        providers.gradleProperty("ahupPython")
            .orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/package_ahup.py"),
        projectDir.absolutePath,
        layout.buildDirectory.file("outputs/apk/release/storage_migration-release-unsigned.apk").get().asFile.absolutePath,
        rootProject.file("dist/ahup/storage_migration.ahup").absolutePath,
        "--id", "storage_migration", "--title", "旧存储迁移", "--summary", "旧存储迁移",
        "--version", "1.0.0", "--version-code", "1", "--author", "AHUTong",
        "--entry", "com.ahu.ahutong.plugin.storage_migration.StorageMigrationPlugin",
        "--capabilities", "PLUGIN_STORAGE",
        "--hidden",
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
    if (name == "releaseRuntimeClasspath" || name == "debugRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath") || name.endsWith("UnitTestCompileClasspath")) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
}
