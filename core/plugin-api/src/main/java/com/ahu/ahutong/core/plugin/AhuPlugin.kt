package com.ahu.ahutong.core.plugin

import androidx.compose.runtime.Composable

/**
 * 可下载 UI 运行时内部的功能契约，不是原生 APK 的外层 ABI。
 * APK 仅使用 core:host-api 的 HostPlugin（Android/JDK 参数、返回 View）。
 * 业务组件通过 ComposePluginEntry 将本接口接入该 View 边界。
 *
 * 生命周期：插件入口卡片出现在小工具页；点击进入后宿主调用 [Entry] 渲染插件的全部内容。
 * 插件内部页面跳转由插件自己管（状态切换或自建 NavHost），宿主只给一扇门。
 *
 * 硬约束（违反会被模块边界测试挡住）：
 * - 插件模块只许依赖 :core:plugin-api + :core:designsystem + :core:common + :core:network；
 * - 不许 import app 内部实现（ui.screen / ui.state / data.dao / data.crawler / 会话实现…）；
 * - UI 一律用设计系统组件（AppCard/AppButton/AppDialog…），自动获得三主题适配；
 * - 网络必须走 [PluginHostServices.http]，不许自己 new OkHttpClient（R8 规则会拦）。
 */
interface AhuPlugin {
    val meta: PluginMeta

    /** 声明需要的能力；宿主按声明提供，未声明的能力调用即抛 [PluginCapabilityDeniedException]。 */
    val capabilities: Set<PluginCapability> get() = emptySet()

    /** 插件入口 Composable：宿主在小工具页点击后全屏渲染它。 */
    @Composable
    fun Entry(host: PluginHostServices)
}
