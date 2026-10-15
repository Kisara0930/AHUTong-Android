val abiRules = rootProject.layout.buildDirectory.file("generated/runtime-abi/shared-runtime.pro")
val generateRuntimeAbi by tasks.registering(Exec::class) {
    val consumers = rootProject.subprojects.filter { it.path.startsWith(":plugins:") && it.name != "shared_runtime" }
    dependsOn(consumers.map { "${it.path}:assembleRelease" })
    inputs.files(consumers.map { it.layout.buildDirectory.file("outputs/apk/release/${it.name}-release-unsigned.apk") })
    inputs.file(rootProject.file("plugins/catalog.json"))
    outputs.file(abiRules)
    commandLine(providers.gradleProperty("ahupPython").orElse(providers.environmentVariable("PYTHON")).getOrElse("python"),
        rootProject.file("scripts/generate_runtime_abi.py"), "--output", abiRules.get().asFile.absolutePath)
}
tasks.matching { it.name == "minifyReleaseWithR8" || it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(generateRuntimeAbi) }
