package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.plugin.bathroom.BathroomCurrentTimeRequest
import com.ahu.ahutong.plugin.bathroom.BathroomOrderResponse
import com.ahu.ahutong.plugin.bathroom.BathroomPayInfoRequest
import com.ahu.ahutong.plugin.bathroom.BathroomPayPrepareRequest
import com.ahu.ahutong.plugin.bathroom.BathroomPayPrepareResponse
import com.ahu.ahutong.plugin.bathroom.BathroomPayRequest
import com.ahu.ahutong.plugin.bathroom.BathroomRequest
import com.ahu.ahutong.plugin.bathroom.PayResponse
import com.ahu.ahutong.plugin.bathroom.RequestBody
import com.ahu.ahutong.plugin.bathroom.BathroomTelInfo
import com.ahu.ahutong.plugin.bathroom.BathroomDepositSource
import com.ahu.ahutong.plugin.bathroom.BathroomPayment
import com.ahu.ahutong.plugin.bathroom.RechargeReceipt
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps the complete five-request bathroom payment protocol behind the feature seam. */
class RepositoryBathroomDepositSource(private val gateway: BathroomGateway, private val storage: com.ahu.ahutong.core.plugin.PluginStorage) : BathroomDepositSource {

    override suspend fun bathroomInfo(bathroom: String, tel: String): AhuResult<BathroomTelInfo> =
        withContext(Dispatchers.IO) { gateway.getBathroomInfo(bathroom, tel) }

    override suspend fun pay(payment: BathroomPayment): AhuResult<RechargeReceipt> =
        withContext(Dispatchers.IO) {
            val result = BathroomPaymentProtocol(::submitPayment).pay(payment)
            if (result is AhuResult.Success) storage.putString("phone", payment.account.telPhone)
            result
        }

    override fun savedPhone(): String? = storage.getString("phone")

    private suspend fun submitPayment(request: RequestBody): String {
        val result = gateway.pay(request)
        val httpResponse = result.valueOrNull()
            ?: throw PaymentFailure(
                result.errorOrNull()?.toUserMessage()?.takeIf { it.isNotBlank() } ?: "支付接口无响应"
            )
        val responseBody = if (httpResponse.isSuccessful) {
            httpResponse.body()?.string()
        } else {
            httpResponse.errorBody()?.string()
        }
        if (!httpResponse.isSuccessful) {
            throw PaymentFailure(
                responseBody?.takeIf { it.isNotBlank() }
                    ?: result.errorOrNull()?.toUserMessage()?.takeIf { it.isNotBlank() }
                    ?: "支付接口请求失败（${httpResponse.code()}）"
            )
        }
        return responseBody?.takeIf { it.isNotBlank() }
            ?: throw PaymentFailure("支付接口返回空数据")
    }
}

internal class BathroomPaymentProtocol(
    private val submit: suspend (RequestBody) -> String
) {
    suspend fun pay(payment: BathroomPayment): AhuResult<RechargeReceipt> {
        var stage = "创建订单"
        return try {
            payment.account.myCustomInfo = "房间：${payment.account.telPhone}"
            val thirdPartyJson = GSON.toJson(payment.account)

            val orderResponse = GSON.fromJson(
                submit(BathroomRequest(payment.bathroom, payment.amount, thirdPartyJson)),
                BathroomOrderResponse::class.java
            )
            val orderId = orderResponse.data?.orderid
            if (orderResponse.code != 200 || !orderResponse.success || orderId.isNullOrBlank()) {
                throw PaymentFailure(orderResponse.msg.ifBlank { "创建浴室缴费订单失败" })
            }

            stage = "初始化支付通道"
            requireSuccessfulBusinessResponse(
                submit(BathroomPayInfoRequest(orderId)),
                "初始化支付通道失败"
            )

            stage = "获取支付密码映射"
            val prepareResponse = GSON.fromJson(
                submit(BathroomPayPrepareRequest(orderId)),
                BathroomPayPrepareResponse::class.java
            )
            val passwordMapping = prepareResponse.data?.passwordMap?.entries?.singleOrNull()
            if (prepareResponse.code != 200 || !prepareResponse.success || passwordMapping == null) {
                throw PaymentFailure(prepareResponse.msg.ifBlank { "获取支付密码映射失败" })
            }

            stage = "同步支付时间"
            requireSuccessfulBusinessResponse(
                submit(BathroomCurrentTimeRequest()),
                "同步支付时间失败"
            )

            stage = "提交扣款"
            val payResponse = GSON.fromJson(
                submit(
                    BathroomPayRequest(
                        orderId = orderId,
                        plaintext = payment.password,
                        uuid = passwordMapping.key,
                        passwordMap = passwordMapping.value
                    )
                ),
                PayResponse::class.java
            )
            if (payResponse.code != 200 || !payResponse.success) {
                throw PaymentFailure(payResponse.msg.ifBlank { "浴室缴费失败" })
            }

            AhuResult.Success(RechargeReceipt(payResponse.data.ifBlank { orderId }))
        } catch (error: CancellationException) {
            throw error
        } catch (error: UnknownHostException) {
            failure("网络不可用，请检查网络连接")
        } catch (error: SocketTimeoutException) {
            failure("$stage 超时，请先核对余额后再重试")
        } catch (error: IOException) {
            failure("网络连接失败，请重试")
        } catch (error: Exception) {
            failure(error.message?.takeIf { it.isNotBlank() } ?: "浴室缴费失败，请重试")
        }
    }

    private fun requireSuccessfulBusinessResponse(json: String, fallbackMessage: String) {
        val response = JsonParser.parseString(json).asJsonObject
        val code = response.get("code")?.asInt
        if (code != 200) {
            val message = response.get("msg")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
            throw PaymentFailure(message ?: fallbackMessage)
        }
    }

    private fun failure(message: String): AhuResult.Failure =
        AhuResult.Failure(AhuError.Unknown(message))

    private companion object {
        val GSON = Gson()
    }
}

private class PaymentFailure(message: String) : Exception(message)
