package com.ahu.ahutong.data.model

/**
 * 校园卡数据源：原有安大系统 与 用户自定义 API。
 *
 * 两套系统鉴权完全独立（原有走 CAS 换 token，自定义走 OAuth2 密码模式），
 * 因此这里只表达「用哪一套」，不代表任何具体端点。切换入口在设置页。
 */
enum class CampusCardProvider(val storageValue: String) {
    /** 原有：安大 ycard / adwmh。 */
    AHU("ahu"),

    /** 自定义：用户自建一卡通服务。 */
    CUSTOM("custom");

    companion object {
        fun fromStorage(value: String?): CampusCardProvider =
            entries.firstOrNull { it.storageValue == value } ?: AHU
    }
}
