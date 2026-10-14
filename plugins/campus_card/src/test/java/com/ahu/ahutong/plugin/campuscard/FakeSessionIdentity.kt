package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.data.model.User
import com.ahu.ahutong.data.session.SessionIdentity

/**
 * [SessionIdentity] 的 fake：登录态可推着走。
 *
 * 充值 feature 的用例靠它回答「本机有没有登录用户」，因此它随 feature 住在模块自己的测试源集里。
 * 跨模块共享测试代码要么走 testFixtures 变体、要么各带一份；这个 fake 只有几行，各带一份更省事，
 * 也与 :feature:grade / :feature:settings / :feature:xuexiaotong 的既有做法一致。
 */
class FakeSessionIdentity(
    var loggedIn: Boolean = true,
    var user: User? = null
) : SessionIdentity {

    override fun isLoggedIn(): Boolean = loggedIn

    override fun currentUser(): User? = user
}
