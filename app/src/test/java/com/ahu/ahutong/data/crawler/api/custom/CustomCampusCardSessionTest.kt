package com.ahu.ahutong.data.crawler.api.custom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 会话代际的 JVM 测试。
 *
 * 完整登录要跑网络与 Android 的 PreferencesManager/Keystore，属于 instrumentation 范围；
 * 这里只钉住那条纯逻辑：改密/清凭据（[CustomCampusCardSession.clear]）之后，在途登录的结果
 * 必须被丢弃，不能把旧密码换来的 token 写回内存。
 */
class CustomCampusCardSessionTest {

    @Test
    fun `a login whose generation is unchanged commits its token`() {
        CustomCampusCardSession.clear()
        val generation = CustomCampusCardSession.currentGeneration()

        val committed = CustomCampusCardSession.commitLoginResult(generation, "token-1", "refresh-1")

        assertEquals("token-1", committed)
        assertEquals("token-1", CustomCampusCardSession.getToken())
    }

    @Test
    fun `clearing the session discards an in-flight login result`() {
        CustomCampusCardSession.clear()
        val generation = CustomCampusCardSession.currentGeneration()

        // 登录还在飞，用户改了密码 / 清了凭据。
        CustomCampusCardSession.clear()

        val committed = CustomCampusCardSession.commitLoginResult(generation, "old-token", "old-refresh")

        assertNull(committed)
        assertNull(CustomCampusCardSession.getToken())
    }
}
