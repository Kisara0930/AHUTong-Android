package com.ahu.ahutong.data

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.google.gson.JsonParseException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 异常 → 统一错误模型的**唯一**映射点（ADR 0001）。
 * 仓储与适配器抛出/捕获异常后一律经此转换，避免每处自行判断"算什么错"。
 */
fun Throwable.toAhuError(): AhuError = when (this) {
    is SocketTimeoutException -> AhuError.Timeout
    is UnknownHostException, is ConnectException -> AhuError.Network
    is IOException -> AhuError.Network
    is JsonParseException, is IllegalStateException ->
        AhuError.ProtocolChanged(message.orEmpty().ifBlank { "响应格式与预期不符" })
    else -> AhuError.Unknown(message.orEmpty().ifBlank { this::class.java.simpleName })
}

/**
 * Kotlin `Result` → 统一结果模型。
 *
 * Rust SDK 与部分底层接口仍以 `Result` 暴露失败（它们不认识业务错误类型），
 * 这里是这些边界进入应用层的唯一转换点。
 */
fun <T> Result<T>.toAhuResult(): AhuResult<T> = fold(
    onSuccess = { AhuResult.Success(it) },
    onFailure = { AhuResult.Failure(it.toAhuError()) }
)

