package com.ahu.ahutong.plugin.account.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.data.model.LoginOutcome
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.account.AccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The original login presentation consumes this adapter; credentials remain owned by the account component. */
class LoginViewModel(private val session: AccountSession) : ViewModel() {
    var state by mutableStateOf(LoginState.Idle)
    var failureMessage by mutableStateOf("")
    var succeedMessage by mutableStateOf("")
    private var webGeneration = -1L

    fun loginWithCrawler(userID: String, password: String) = viewModelScope.launch {
        if (state == LoginState.InProgress) return@launch
        state = LoginState.InProgress
        try {
            val result = withContext(Dispatchers.IO) { session.signIn(userID, password) }
            when (val outcome = result.valueOrNull()) {
                is LoginOutcome.Success -> {
                    succeedMessage = "欢迎，${outcome.user.name}！"
                    state = LoginState.Succeeded
                }
                is LoginOutcome.JwxtWebVerificationRequired -> {
                    webGeneration = AccountGeneration.current
                    state = LoginState.WebVerification
                }
                null -> failWebVerification(result.errorOrNull()?.toUserMessage() ?: "登录失败，请重试")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failWebVerification(failure.message ?: "登录失败，请重试") }
    }

    fun completeWebVerification(cookiesJson: String, password: String) = viewModelScope.launch {
        if (webGeneration != AccountGeneration.current) { failWebVerification("登录信息已失效，请重新登录"); return@launch }
        state = LoginState.InProgress
        try {
            val result = withContext(Dispatchers.IO) { session.importWebCookies(cookiesJson, webGeneration) }
            if (!result.isSuccess) { failWebVerification(result.errorOrNull()?.toUserMessage() ?: "教务验证失败"); return@launch }
            session.completeWebVerification()
            succeedMessage = "欢迎，${SessionStore.currentUser()?.name.orEmpty()}！"
            state = LoginState.Succeeded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failWebVerification(failure.message ?: "教务验证失败") }
    }

    fun failWebVerification(message: String) {
        webGeneration = -1L
        failureMessage = message
        state = LoginState.Failed
    }
}

enum class LoginState { Idle, InProgress, WebVerification, Failed, Succeeded }
