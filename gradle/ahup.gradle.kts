import groovy.json.JsonSlurper

val catalog = JsonSlurper().parse(rootProject.file("plugins/catalog.json")) as Map<*, *>
val components = catalog["components"] as List<Map<String, Any?>>
val component = components.single { it["id"] == project.name }
tasks.register<Exec>("packageAhup") {
    group = "ahup"
    dependsOn("assembleRelease")
    val arguments = mutableListOf(
        providers.gradleProperty("ahupPython").orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/package_ahup.py").absolutePath, projectDir.absolutePath,
        layout.buildDirectory.file("outputs/apk/release/${project.name}-release-unsigned.apk").get().asFile.absolutePath,
        rootProject.file("dist/ahup/${project.name}.ahup").absolutePath,
        "--id", project.name, "--title", component["title"].toString(), "--summary", component["summary"].toString(),
        "--version", component["versionName"].toString(), "--version-code", component["versionCode"].toString(),
        "--entry", component["entryClass"].toString(), "--author", "AHUTong",
        "--host-api-version", catalog["hostApiVersion"].toString(), "--runtime-version", catalog["runtimeVersion"].toString(),
        "--capabilities", (component["capabilities"] as List<*>).joinToString(","),
        "--forbid-class-prefix", "com.ahu.ahutong.hostapi.",
        "--java", File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").absolutePath
    )
    (component["dependencies"] as List<Map<String, Any?>>).forEach {
        arguments += listOf("--dependency", "${it["id"]}:${it["minVersion"]}" + (it["maxVersion"]?.let { maximum -> ":$maximum" } ?: ""))
    }
    mapOf("required" to "--required", "homeCard" to "--home-card", "autoStart" to "--auto-start", "foreground" to "--foreground").forEach { (key, flag) ->
        if (component[key] == true) arguments += flag
    }
    if (component["userVisible"] == false) arguments += "--hidden"
    if (component["role"] == "feature" || component["role"] == "ui") {
        arguments += listOf("--forbid-class-prefix", "androidx.compose.", "--forbid-class-prefix", "kotlin.")
    }
    commandLine(arguments)
}
