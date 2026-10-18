package com.ahu.ahutong.core.storage

/**
 * 自定义校园卡会话的失效入口。
 *
 * 切换数据源、修改账号密码或清除凭据后，内存里的 token 必须立即作废，否则后续查询可能
 * 继续用旧凭据的会话。会话实现（token 只存内存）住在 :app，feature 拿不到，因此这里沿用
 * [CampusCardModeSetting] 的窄端口做法：默认空实现，既有 fake 不炸，生产在 :app 装配。
 */
interface CampusCardSessionControl {

    /** 使当前自定义校园卡会话立即失效，下次请求重新登录。 */
    fun invalidate() {}
}
