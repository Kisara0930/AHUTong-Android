package com.ahu.ahutong.feature.circle

import androidx.compose.runtime.Composable
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginMeta

/**
 * 校园圈子插件（只读）。
 *
 * ServiceLoader 发现要求：**public 无参构造**（不能用 Kotlin object）。
 * 服务索引见 src/main/resources/META-INF/services/。
 */
class CirclePlugin : AhuPlugin {

    override val meta = PluginMeta(
        id = "campus_circle",
        title = "校园圈子",
        summary = "安大 BBS 只读浏览",
        // 插件列表图标来自签名包内 icon.png；页面资源来自独立链接的 resources.apk。
        icon = PluginIcon.Resource(0),
        tint = 0xFF5C6BC0, // 靛蓝，与现有小工具配色同族
        version = "1.0.0",
        author = "AHUTong"
    )

    override val capabilities = setOf(
        PluginCapability.NETWORK,
        PluginCapability.PLUGIN_STORAGE
    )

    @Composable
    override fun Entry(host: PluginHostServices) {
        CircleRoot(host)
    }
}
