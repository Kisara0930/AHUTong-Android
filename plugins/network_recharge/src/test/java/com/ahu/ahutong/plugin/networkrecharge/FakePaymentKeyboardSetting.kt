package com.ahu.ahutong.plugin.networkrecharge

import com.ahu.ahutong.core.storage.PaymentKeyboardSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [PaymentKeyboardSetting] 的 fake：默认用系统键盘，需要时改成内置键盘。 */
class FakePaymentKeyboardSetting(
    initial: Boolean = false
) : PaymentKeyboardSetting {

    val builtIn = MutableStateFlow(initial)

    override val useBuiltInSecurePasswordKeyboard: Flow<Boolean> = builtIn
}
