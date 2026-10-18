package com.ahu.ahutong.data.crawler

import com.ahu.ahutong.core.storage.CampusCardSessionControl
import com.ahu.ahutong.data.crawler.api.custom.CustomCampusCardSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CampusCardSessionControl] 的生产实现：把设置页的失效动作接到内存会话上。
 *
 * 只能住在 :app——[CustomCampusCardSession] 与设置 feature 之间隔着模块边界，
 * feature 拿到的是 [CampusCardSessionControl] 这个窄接口。
 */
@Singleton
class CustomCampusCardSessionControl @Inject constructor() : CampusCardSessionControl {

    override fun invalidate() = CustomCampusCardSession.clear()
}
