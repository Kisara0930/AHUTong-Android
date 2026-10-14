package com.ahu.ahutong.plugin.storage_migration

import androidx.compose.runtime.Composable
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.security.LegacyNativeStorage
import com.ahu.ahutong.sdk.nativebridge.StorageNative

/** A bootstrap dependency only for installations that still have a GuiXu directory. */
class StorageMigrationPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("storage_migration", "旧存储迁移", "将旧 GuiXu 数据迁入加密存储并保留可恢复的加密归档",
        PluginIcon.Resource(0), 0xFF667788, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.PLUGIN_STORAGE)
    override fun onCreate(environment: PluginEnvironment) {
        StorageNative.ensureLoaded()
        val reader = GuiXuMigration(environment.context.applicationContext)
        reader.migrate()
        LegacyNativeStorage.install(reader)
    }
    @Composable override fun Entry(host: PluginHostServices) {
        androidx.compose.material3.Text("旧存储迁移组件仅用于升级恢复。迁移后的账号与缓存保存在加密存储中，原始备份使用独立密钥加密归档。")
    }
}
