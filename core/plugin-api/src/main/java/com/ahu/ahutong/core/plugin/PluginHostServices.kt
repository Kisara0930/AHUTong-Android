package com.ahu.ahutong.core.plugin

import android.content.Context
import okhttp3.OkHttpClient

/**
 * 下载运行时内部的能力入口，允许使用运行时拥有的共享库。
 * 原生 APK 不引用本接口；其外层契约为 core:host-api/HostEnvironment。
 *
 * 设计纪律（宿主实现方必读）：
 * - [http] 必须是**无全局会话**的裸客户端：不带教务/一卡通/学习通的任何 Cookie 与拦截器。
 *   插件要访问自己的服务就自带 Header，不许顺走主 App 的登录态。
 * - [storage] 必须按插件 id 隔离命名空间。
 * - 插件拿不到的东西就是故意拿不到的（学号、教务会话、其他插件的数据）。
 */
interface PluginHostServices {
    val appContext: Context

    /** 裸 HTTP 客户端（无 CookieJar、无自动登录）。要求插件声明了 [PluginCapability.NETWORK]。 */
    fun http(): OkHttpClient

    /** 插件隔离存储。要求插件声明了 [PluginCapability.PLUGIN_STORAGE]。 */
    fun storage(): PluginStorage
}

/** 能力未声明时宿主的统一拒绝。 */
class PluginCapabilityDeniedException(capability: PluginCapability, pluginId: String) :
    IllegalStateException("插件 $pluginId 未声明能力 $capability")
