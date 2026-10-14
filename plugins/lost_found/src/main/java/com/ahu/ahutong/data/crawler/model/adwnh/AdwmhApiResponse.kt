package com.ahu.ahutong.data.crawler.model.adwnh

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult

/**
 * 安大智慧（ADWMH）接口的线上响应格式：`{ code, msg, data }`。
 * 它属于**协议层 DTO**，只在适配器内部出现，不兼任全应用的返回类型（那是 AhuResult 的职责）。
 *
 * 字段名保持不变是刻意的：Gson 反序列化行为与迁移前一致。
 */
data class AdwmhApiResponse<T>(
    val data: T? = null,
    val msg: String = "",
    val code: Int = -1
)

/** 协议 DTO → 统一结果模型（与旧语义一一对应）。 */
fun <T> AdwmhApiResponse<T>.toAhuResult(): AhuResult<T> {
    val payload = data
    return when {
        code == 0 && payload != null -> AhuResult.Success(payload)
        code == 401 -> AhuResult.Failure(AhuError.Unauthorized(msg.ifBlank { "登录态已失效，请重新登录" }))
        code == 0 -> AhuResult.Failure(AhuError.ProtocolChanged(msg.ifBlank { "响应缺少数据" }))
        else -> AhuResult.Failure(AhuError.Server(code, msg))
    }
}
