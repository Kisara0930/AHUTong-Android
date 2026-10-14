package com.ahu.ahutong.ui.plugin

/** Host-owned local migration runs before reading account/bootstrap state, including on later starts. */
object PluginBootstrapHooks {
    @Volatile
    var handler: ((android.content.Context) -> Unit)? = null

    fun beforeBootstrap(context: android.content.Context) {
        handler?.invoke(context.applicationContext)
    }
}
