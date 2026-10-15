package com.ahu.ahutong.ui.plugin

import android.content.Context
import android.net.Uri
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCameraService
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginCapabilityDeniedException
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginHostV2
import com.ahu.ahutong.core.plugin.PluginStorage
import com.ahu.ahutong.data.network.AhuHttp
import okhttp3.OkHttpClient
import java.io.File

/**
 * 宿主能力实现：插件拿到的一切都经过这里。
 *
 * - HTTP：AhuHttp.plain() 的裸客户端——无 CookieJar、无自动登录、无全局拦截器，
 *   插件顺不走主 App 的任何登录态（教务/一卡通/学习通）。
 * - 存储：SharedPreferences 命名空间 plugin_<id>，插件间互不可见；
 *   未声明 PLUGIN_STORAGE 的插件调用即拒。
 * - 相机（PluginHostV2）：宿主持有 CAMERA 权限并统一处理运行时授权；
 *   未声明 CAMERA 的插件调用 camera() 即拒。插件用 `host is PluginHostV2` 探测旧宿主。
 * - 二进制文件目录（PluginHostV2）：filesDir/ahup_plugins/<id>/，卸载连带清理。
 * - Photo Picker（PluginHostV2）：零权限系统照片选择器，无门控。
 */
class PluginHostServicesImpl(
    override val appContext: Context,
    private val plugin: AhuPlugin,
    private val onClose: () -> Unit = {}
) : PluginHostServices, PluginHostV2 {

    private val httpClient: OkHttpClient by lazy {
        ensure(PluginCapability.NETWORK)
        AhuHttp.plain().build()
    }

    private val pluginStorage: PluginStorage by lazy {
        SharedPrefsPluginStorage(appContext, plugin.meta.id)
    }

    private val cameraService: PluginCameraService by lazy {
        ensure(PluginCapability.CAMERA)
        // CameraX is an optional library component. Its implementation is not linked into the APK or SDK.
        val environment = com.ahu.ahutong.runtime.KernelBindings.requireEnvironment()
        val camera = environment.component("camera_runtime")
        camera.loader.loadClass("com.ahu.ahutong.ui.plugin.PluginCameraServiceImpl")
            .getConstructor(Context::class.java).newInstance(appContext) as PluginCameraService
    }

    override fun http(): OkHttpClient {
        ensure(PluginCapability.NETWORK)
        return httpClient
    }

    override fun storage(): PluginStorage {
        ensure(PluginCapability.PLUGIN_STORAGE)
        return pluginStorage
    }

    /* ---------------- PluginHostV2 ---------------- */

    override fun camera(): PluginCameraService {
        ensure(PluginCapability.CAMERA)
        return cameraService
    }

    override fun pluginFilesDir(): File {
        ensure(PluginCapability.PLUGIN_STORAGE)
        return File(appContext.filesDir, "ahup_plugins/${plugin.meta.id}").apply { mkdirs() }
    }

    override fun pickImage(onResult: (Uri?) -> Unit) {
        // Photo Picker 零权限，无门控
        PluginHostLauncherHub.pickImage(onResult)
    }

    override fun close() {
        onClose()
    }

    private fun ensure(capability: PluginCapability) {
        if (capability !in plugin.capabilities) {
            throw PluginCapabilityDeniedException(capability, plugin.meta.id)
        }
    }
}

/** SharedPreferences 实现的插件隔离存储。 */
private class SharedPrefsPluginStorage(context: Context, pluginId: String) : PluginStorage {
    private val prefs = context.getSharedPreferences("plugin_$pluginId", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
    override fun getBoolean(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
