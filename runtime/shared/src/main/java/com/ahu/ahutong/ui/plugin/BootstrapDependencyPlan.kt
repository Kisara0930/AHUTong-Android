package com.ahu.ahutong.ui.plugin

/** Pure planning over verified manifests; this never installs, enables or removes anything. */
data class BootstrapDependencyPlan(
    val requiredIds: Set<String>,
    val installIds: Set<String>,
    val selected: Map<String, AhupManifest>
) {
    companion object {
        const val MAX_EMBEDDED_PLUGINS = 128

        fun resolve(installed: Collection<AhupManifest>, embedded: Collection<AhupManifest>,
            requiredIds: Set<String>): BootstrapDependencyPlan {
            require(embedded.size <= MAX_EMBEDDED_PLUGINS) { "基础插件数量超限" }
            val current = installed.associateBy { it.id }
            val bundled = embedded.associateBy { it.id }
            require(current.size == installed.size && bundled.size == embedded.size) { "基础插件包含重复身份" }
            val selected = current.toMutableMap()
            val required = linkedSetOf<String>()
            val installs = linkedSetOf<String>()
            try {
                fun collect(id: String) {
                    if (!required.add(id)) return
                    val old = current[id]
                    val asset = bundled[id]
                    val choice = if (asset != null && (old == null || asset.versionCode > old.versionCode)) {
                        installs.add(id)
                        asset
                    } else old ?: throw IllegalArgumentException("基础插件缺少依赖：$id")
                    selected[id] = choice
                    choice.dependencies.forEach { collect(it.id) }
                }
                (requiredIds + embedded.filter { it.required }.map { it.id }).forEach(::collect)
                // Keep every installed optional plugin in the graph, including disabled ones.
                AhupPackageVerifier.checkDependencies(selected.values)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("基础插件依赖不兼容，请先更新或卸载冲突插件：${error.message}", error)
            }
            return BootstrapDependencyPlan(required, installs, selected.toMap())
        }
    }
}
