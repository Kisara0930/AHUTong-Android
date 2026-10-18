package com.ahu.ahutong.data.crawler

import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.data.dao.PreferencesManager
import com.ahu.ahutong.data.model.CampusCardProvider
import kotlinx.coroutines.flow.first

/**
 * 校园卡数据源的运行时读取点。
 *
 * 设置值存在 DataStore（[PreferencesManager]），但 [AHURepository] 与各适配器都是
 * 非注入的 object / 直接构造，拿不到 Hilt 图。这里沿用 `RepositoryManager` 的既有做法
 * ——用全局 [AppEnvironmentHolder] 的 Context 现取一次设置（DataStore 有内存快照，读取很廉价）。
 */
object CampusCardProviderAccess {

    suspend fun current(): CampusCardProvider =
        PreferencesManager(AppEnvironmentHolder.context()).campusCardProvider.first()

    /** 当前是否使用用户自定义数据源。 */
    suspend fun isCustom(): Boolean = current() == CampusCardProvider.CUSTOM
}
