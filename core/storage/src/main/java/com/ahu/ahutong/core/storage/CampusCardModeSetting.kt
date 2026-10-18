package com.ahu.ahutong.core.storage

import com.ahu.ahutong.data.model.CampusCardProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 校园卡数据源模式（原有 / 自定义）的只读端口。
 *
 * 交电费等 feature 只需要「当前用哪套后端」这一个信号，不该为此依赖整个 [SettingsStore]；
 * 窄接口也让它们的单测 fake 只需实现一个成员。默认给实现，既有 fake 不炸。
 */
interface CampusCardModeSetting {

    val campusCardProvider: Flow<CampusCardProvider>
        get() = flowOf(CampusCardProvider.AHU)
}
