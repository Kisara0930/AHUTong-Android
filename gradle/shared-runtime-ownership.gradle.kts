// Read the committed lock, not another project's live configuration. This works with Gradle isolation.
val sharedLock = rootProject.file("runtime/shared/gradle.lockfile")
check(sharedLock.isFile) { "Build and lock :runtime:shared before linking independent runtime components" }
val sharedModules = sharedLock.readLines().filter { !it.startsWith("#") && it.contains('=') }
    .filter { it.substringAfter('=').split(',').contains("releaseRuntimeClasspath") }
    .map { it.substringBefore('=').split(':') }.filter { it.size == 3 }.map { it[0] to it[1] }.toSet()
check(sharedModules.isNotEmpty()) { "The shared runtime ownership lock is empty" }
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test", ignoreCase = true)) {
        sharedModules.forEach { (group, module) -> exclude(group = group, module = module) }
    }
}
