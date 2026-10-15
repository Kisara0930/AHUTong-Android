package com.ahu.ahutong.ui.plugin

@androidx.annotation.Keep
internal data class CrashPlugin(val id: String, val version: Int, val prefix: String, val libraries: List<String>)
@androidx.annotation.Keep
internal data class PluginCrash(val plugin: CrashPlugin?, val kind: String, val at: Long)

internal object CrashAttribution {
    fun javaOwner(classNames: List<String>, plugins: List<CrashPlugin>): CrashPlugin? = classNames.firstNotNullOfOrNull { name ->
        plugins.filter { name == it.prefix || name.startsWith(it.prefix + ".") }.maxByOrNull { it.prefix.length }
    }
    fun nativeOwner(trace: String, plugins: List<CrashPlugin>): CrashPlugin? {
        val matches = plugins.filter { plugin -> plugin.libraries.any { library -> trace.contains(library) } }
        return matches.singleOrNull()
    }
}
