package com.ahu.ahutong.plugin.networkrecharge

import android.util.Log
import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.networkrecharge.NetworkAccountPayInfoResponse
import com.ahu.ahutong.plugin.networkrecharge.NetworkFeeInfoResponse
import com.ahu.ahutong.plugin.networkrecharge.NetworkFeeItemPageResponse
import com.ahu.ahutong.plugin.networkrecharge.NetworkFinalPayResponse
import com.ahu.ahutong.plugin.networkrecharge.NetworkOrderData
import com.ahu.ahutong.plugin.networkrecharge.NetworkOrderResponse
import com.ahu.ahutong.plugin.networkrecharge.NetworkThirdDataResponse
import com.ahu.ahutong.plugin.networkrecharge.buildSignedPaymentFormBody
import com.ahu.ahutong.plugin.networkrecharge.NetworkFeeItem
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSnapshot
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSource
import com.ahu.ahutong.plugin.networkrecharge.NetworkThirdPartyData
import com.ahu.ahutong.plugin.networkrecharge.RechargeReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response

private const val NETWORK_FEE_ITEM_ID = "431"
private const val NETWORK_FEE_ENTRY_APP_ID = "75"
private const val YCARD_ORIGIN = "https://ycard.ahu.edu.cn"
private const val NETWORK_ENTRY_REFERER = "https://ycard.ahu.edu.cn/plat/dating?index=1"

/** Keeps the complete network-recharge protocol behind the feature seam. */
class RepositoryNetworkRechargeSource(private val gateway: YcardGateway) : NetworkRechargeSource {

    override suspend fun load(): AhuResult<NetworkRechargeSnapshot> = withContext(Dispatchers.IO) {
        try {
            if (gateway.token().isNullOrBlank()) {
                return@withContext AhuResult.Failure(
                    AhuError.Server(-1, "校园卡登录凭证暂未就绪，请稍后重试")
                )
            }

            warmUpEntry()
            when (val selected = fetchSelectState()) {
                is AhuResult.Failure -> return@withContext selected
                is AhuResult.Success -> Unit
            }

            val feeItem = when (val result = fetchFeeItem()) {
                is AhuResult.Failure -> return@withContext result
                is AhuResult.Success -> result.value
            }
            val info = when (val result = fetchNetworkInfo()) {
                is AhuResult.Failure -> return@withContext result
                is AhuResult.Success -> result.value
            }

            AhuResult.Success(
                NetworkRechargeSnapshot(
                    feeItem = feeItem,
                    showData = info.showData,
                    paymentData = info.paymentData
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to load network recharge info", error)
            AhuResult.Failure(AhuError.Unknown(error.message ?: "网费充值信息加载失败"))
        }
    }

    override suspend fun pay(
        snapshot: NetworkRechargeSnapshot,
        amount: String,
        password: String
    ): AhuResult<RechargeReceipt> = withContext(Dispatchers.IO) {
        try {
            val order = when (val result = getPaymentOrder(amount, snapshot.paymentData)) {
                is AhuResult.Failure -> return@withContext result.withFallback("创建订单失败")
                is AhuResult.Success -> result.value
            }
            val passwordMapping = when (val result = getAccountPayInfo(order.orderId)) {
                is AhuResult.Failure -> return@withContext result.withFallback("获取支付信息失败")
                is AhuResult.Success -> result.value
            }
            val cipherMap = buildPasswordCipherMap(passwordMapping.second)
            val cipherText = password.map { digit ->
                cipherMap[digit] ?: error("无效的校园卡密码映射")
            }.joinToString("")

            when (val result = executeFinalPay(order.orderId, cipherText, passwordMapping.first)) {
                is AhuResult.Failure -> result.withFallback("网费充值失败")
                is AhuResult.Success -> AhuResult.Success(RechargeReceipt(order.orderId))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to pay network recharge", error)
            AhuResult.Failure(AhuError.Unknown(error.message ?: "网费充值失败，请稍后重试"))
        }
    }

    private fun AhuResult.Failure.withFallback(message: String): AhuResult.Failure =
        if (error.text().isBlank()) AhuResult.Failure(AhuError.Server(-1, message)) else this

    private fun AhuError.text(): String = when (this) {
        AhuError.Network -> "network"
        AhuError.Timeout -> "timeout"
        is AhuError.Unauthorized -> message
        is AhuError.ProtocolChanged -> detail
        is AhuError.Server -> message
        is AhuError.Unknown -> message
    }

    private suspend fun warmUpEntry() {
        val token = gateway.token().orEmpty()
        val url = "$YCARD_ORIGIN/charge/feeitem/toAppitem".toHttpUrl()
            .newBuilder()
            .addQueryParameter("feeitemid", NETWORK_FEE_ITEM_ID)
            .addQueryParameter("appId", NETWORK_FEE_ENTRY_APP_ID)
            .addQueryParameter("loginFrom", "h5")
            .addQueryParameter("synAccessSource", "h5")
            .addQueryParameter("synjones-auth", token)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Referer", NETWORK_ENTRY_REFERER)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0 Mobile Safari/537.36"
            )
            .get()
            .build()
        // The token is a query parameter, so use the existing no-log, no-redirect client.
        gateway.loginRedirectClient.newCall(request).execute().use { response ->
            if (response.code !in 300..399 && !response.isSuccessful) {
                error("网费入口预热失败: ${response.code}")
            }
        }
    }

    private suspend fun fetchSelectState(): AhuResult<Unit> {
        val form = FormBody.Builder()
            .add("feeitemid", NETWORK_FEE_ITEM_ID)
            .add("type", "select")
            .add("level", "0")
            .build()
        return parseJsonResponse({ getFeeItemThirdData(form) }) { body ->
            val response = GSON.fromJson(body, NetworkThirdDataResponse::class.java)
            if (response.code == 200) {
                AhuResult.Success(Unit)
            } else {
                AhuResult.Failure(
                    AhuError.Server(-1, response.msg ?: "网费充值入口预热失败")
                )
            }
        }
    }

    private suspend fun fetchFeeItem(): AhuResult<NetworkFeeItem> =
        parseJsonResponse({ getSingleFeeItem(NETWORK_FEE_ITEM_ID) }) { body ->
            val response = GSON.fromJson(body, NetworkFeeItemPageResponse::class.java)
            val feeItem = response.feeItem
            if (response.code == 200 && feeItem != null) {
                AhuResult.Success(feeItem)
            } else {
                AhuResult.Failure(
                    AhuError.Server(-1, response.msg ?: "网费充值配置加载失败")
                )
            }
        }

    private suspend fun fetchNetworkInfo(): AhuResult<NetworkInfo> {
        val form = FormBody.Builder()
            .add("feeitemid", NETWORK_FEE_ITEM_ID)
            .add("type", "IEC")
            .add("level", "0")
            .build()
        return parseJsonResponse({ getFeeItemThirdData(form) }) { body ->
            val response = GSON.fromJson(body, NetworkFeeInfoResponse::class.java)
            val map = response.map
            val account = map?.data
            if (response.code == 200 && map != null && account != null) {
                AhuResult.Success(NetworkInfo(map.showData.orEmpty(), account))
            } else {
                AhuResult.Failure(
                    AhuError.Server(-1, response.msg ?: "网费账户信息加载失败")
                )
            }
        }
    }

    private suspend fun getPaymentOrder(
        amount: String,
        paymentData: NetworkThirdPartyData
    ): AhuResult<NetworkOrderData> {
        val form = buildSignedPaymentFormBody(
            linkedMapOf(
                "feeitemid" to NETWORK_FEE_ITEM_ID,
                "tranamt" to amount,
                "flag" to "choose",
                "source" to "app",
                "paystep" to "0",
                "abstracts" to "",
                "redirect_url" to "https://ycard.ahu.edu.cn/plat",
                "third_party" to GSON.toJson(paymentData)
            )
        )
        return parseJsonResponse({ pay(form) }) { body ->
            val response = GSON.fromJson(body, NetworkOrderResponse::class.java)
            val data = response.data
            if (response.code == 200 && data != null) {
                AhuResult.Success(data)
            } else {
                AhuResult.Failure(AhuError.Server(-1, response.msg.orEmpty()))
            }
        }
    }

    private suspend fun getAccountPayInfo(orderId: String): AhuResult<Pair<String, String>> {
        val form = buildSignedPaymentFormBody(
            linkedMapOf(
                "paytypeid" to "64",
                "paytype" to "ACCOUNTTSM",
                "paystep" to "2",
                "orderid" to orderId
            )
        )
        return parseJsonResponse({ pay(form) }) { body ->
            val response = GSON.fromJson(body, NetworkAccountPayInfoResponse::class.java)
            val passwordMap = response.data?.passwordMap
            if (response.code == 200 && !passwordMap.isNullOrEmpty()) {
                val entry = passwordMap.entries.first()
                AhuResult.Success(entry.key to entry.value)
            } else {
                AhuResult.Failure(AhuError.Server(-1, response.msg.orEmpty()))
            }
        }
    }

    private suspend fun executeFinalPay(
        orderId: String,
        password: String,
        uuid: String
    ): AhuResult<String> {
        val form = buildSignedPaymentFormBody(
            linkedMapOf(
                "orderid" to orderId,
                "paystep" to "2",
                "paytype" to "ACCOUNTTSM",
                "paytypeid" to "64",
                "userAgent" to "h5",
                "ccctype" to "000",
                "password" to password,
                "uuid" to uuid,
                "isWX" to "0"
            )
        )
        return parseJsonResponse({ pay(form) }) { body ->
            val response = GSON.fromJson(body, NetworkFinalPayResponse::class.java)
            val data = response.data
            if (response.code == 200 && response.success && !data.isNullOrBlank()) {
                AhuResult.Success(data)
            } else {
                AhuResult.Failure(AhuError.Server(-1, response.msg.orEmpty()))
            }
        }
    }

    private suspend fun <T> parseJsonResponse(
        call: suspend YcardApi.() -> Response<ResponseBody>,
        parse: (String) -> AhuResult<T>
    ): AhuResult<T> {
        val body = when (val result = authorizedText(call)) {
            is AhuResult.Failure -> return result
            is AhuResult.Success -> result.value
        }
        return try {
            if (body.isBlank()) {
                AhuResult.Failure(AhuError.ProtocolChanged("服务器返回内容为空"))
            } else {
                parse(body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AhuResult.Failure(AhuError.Unknown(error.message ?: "发生未知错误"))
        }
    }

    private suspend fun authorizedText(
        call: suspend YcardApi.() -> Response<ResponseBody>
    ): AhuResult<String> {
        val response = gateway.authorizedCall(request = call)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return AhuResult.Failure(
                AhuError.Server(response.code(), "请求接口失败: ${response.message()}")
            )
        }
        return AhuResult.Success(response.body()?.string().orEmpty())
    }

    private fun buildPasswordCipherMap(map: String): Map<Char, Char> {
        require(map.length == 10) { "无效的密码映射表" }
        val plainDigits = "0123456789"
        return map.mapIndexed { index, cipherDigit -> cipherDigit to plainDigits[index] }.toMap()
    }

    private data class NetworkInfo(
        val showData: Map<String, String>,
        val paymentData: NetworkThirdPartyData
    )

    private companion object {
        const val TAG = "NetworkRechargeViewModel"
        val GSON = com.google.gson.Gson()
    }
}
