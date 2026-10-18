package com.ahu.ahutong.data.crawler

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.crawler.api.custom.CustomCampusCardApi
import com.ahu.ahutong.data.crawler.api.custom.CustomCampusCardSession
import com.ahu.ahutong.data.crawler.model.ycard.ElectricityCampusApiResponse
import com.ahu.ahutong.data.crawler.model.ycard.ElectricityRoomInfoApiResponse
import com.ahu.ahutong.data.crawler.model.ycard.TurnoverCount
import com.ahu.ahutong.data.crawler.model.ycard.TurnoverPage
import com.ahu.ahutong.data.model.CampusDataItem
import com.ahu.ahutong.data.model.Card
import com.ahu.ahutong.data.model.ElectricityController
import com.ahu.ahutong.data.model.RoomSelectionInfo
import com.ahu.ahutong.data.recharge.ElectricityOptionLevel
import com.ahu.ahutong.data.recharge.ElectricityOptionQuery
import com.ahu.ahutong.data.recharge.ElectricityRoom
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * 用户自定义一卡通的读操作后端（余额 / 账单 / 电费查询）。
 *
 * 复用点：账单直接复用 [TurnoverResponse] 形状，电费复用原有解析模型
 * （[ElectricityCampusApiResponse] / [ElectricityRoomInfoApiResponse]），
 * 只换 host、鉴权与传参方式（query string）。写操作（充值 / 缴费）不在本后端范围内。
 */
object CustomCampusCardBackend {

    /** 该校租户只有一个缴费项（电费）。 */
    private const val FEE_ITEM_ID = "221"

    private val GSON = Gson()

    suspend fun awaitCredential(): Boolean = CustomCampusCardSession.awaitToken() != null

    // ── 余额 ─────────────────────────────────────────────────────────────

    suspend fun balance(): AhuResult<Card> = guard {
        var response = CustomCampusCardApi.authorizedCall { getBalance() }
        if (response.isSuccessful && CustomCampusCardApi.authFailure(response.body()?.code)) {
            response = retryTyped { getBalance() }
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return@guard httpFailure(response.code(), "余额查询失败")
        }
        val body = response.body() ?: return@guard protocolFailure("余额响应为空")
        if (!isSuccessCode(body.code)) {
            return@guard AhuResult.Failure(
                AhuError.Server(body.code, body.message ?: body.msg ?: "余额查询失败")
            )
        }
        val item = body.data?.firstOrNull()
            ?: return@guard AhuResult.Failure(AhuError.Server(-1, "该账号没有可用校园卡账户"))
        val balanceCents = item.elecAccAmt
            ?: return@guard protocolFailure("余额字段缺失，上游服务可能已改版")
        AhuResult.Success(Card().apply { balance = balanceCents / 100.0 })
    }

    // ── 账单 ─────────────────────────────────────────────────────────────

    suspend fun billPage(
        page: Int,
        size: Int,
        timeFrom: String?,
        timeTo: String?,
        type: Int?
    ): AhuResult<TurnoverPage> = guard {
        var response = CustomCampusCardApi.authorizedCall {
            getTurnover(size = size, current = page, timeFrom = timeFrom, timeTo = timeTo, type = type)
        }
        if (response.isSuccessful && CustomCampusCardApi.authFailure(response.body()?.code)) {
            response = retryTyped {
                getTurnover(size = size, current = page, timeFrom = timeFrom, timeTo = timeTo, type = type)
            }
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return@guard httpFailure(response.code(), "账单加载失败")
        }
        val body = response.body() ?: return@guard protocolFailure("账单响应为空")
        if (!isSuccessCode(body.code)) {
            return@guard AhuResult.Failure(
                AhuError.Server(body.code, body.msg ?: "账单加载失败")
            )
        }
        val pageData = body.data ?: return@guard protocolFailure("账单数据为空")
        AhuResult.Success(pageData)
    }

    suspend fun billSummary(timeFrom: String, timeTo: String): AhuResult<TurnoverCount> = guard {
        var response = CustomCampusCardApi.authorizedCall { getTurnoverCount(timeFrom, timeTo) }
        if (response.isSuccessful && CustomCampusCardApi.authFailure(response.body()?.code)) {
            response = retryTyped { getTurnoverCount(timeFrom, timeTo) }
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            // 汇总端点在自定义 host 上未经实测：失败不阻塞列表，这里如实返回失败。
            return@guard httpFailure(response.code(), "账单汇总暂不支持")
        }
        val body = response.body() ?: return@guard protocolFailure("账单汇总响应为空")
        val data = body.data ?: return@guard protocolFailure("账单汇总数据为空")
        AhuResult.Success(data)
    }

    // ── 电费（选项 / 房间详情 / 余额文本） ────────────────────────────────

    suspend fun electricityOptions(
        query: ElectricityOptionQuery
    ): AhuResult<List<CampusDataItem>> = guard {
        val params = optionParams(query)
            ?: return@guard protocolFailure(query.level.missingMessage)
        val body = when (val text = thirdDataBody(params)) {
            is AhuResult.Failure -> return@guard text
            is AhuResult.Success -> text.value
        }
        val parsed = runCatching {
            GSON.fromJson(body, ElectricityCampusApiResponse::class.java)
        }.getOrNull() ?: return@guard protocolFailure("解析数据失败，未找到电控选项")
        parsed.map?.data?.let { AhuResult.Success(it) }
            ?: protocolFailure(query.level.missingMessage)
    }

    suspend fun electricityRoom(
        controller: ElectricityController,
        selection: RoomSelectionInfo
    ): AhuResult<ElectricityRoom> = guard {
        val building = selection.building?.value?.takeIf { it.isNotBlank() }
            ?: return@guard protocolFailure("未选择楼栋")
        val floor = selection.floor?.value?.takeIf { it.isNotBlank() }
            ?: return@guard protocolFailure("未选择楼层")
        val room = selection.room?.value?.takeIf { it.isNotBlank() }
            ?: return@guard protocolFailure("未选择房间")
        val campus = selection.campus?.value?.takeIf { it.isNotBlank() }
        val params = ThirdDataParams(
            type = "IEC",
            level = "4",
            campus = campus,
            building = building,
            floor = floor,
            room = room
        )
        val body = when (val text = thirdDataBody(params)) {
            is AhuResult.Failure -> return@guard text
            is AhuResult.Success -> text.value
        }
        val parsed = runCatching {
            GSON.fromJson(body, ElectricityRoomInfoApiResponse::class.java)
        }.getOrNull() ?: return@guard protocolFailure("解析数据失败，未找到房间信息")
        val map = parsed.map
        map?.data?.let { AhuResult.Success(ElectricityRoom(map.showData?.info, it)) }
            ?: protocolFailure("解析数据失败，未找到房间信息")
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun optionParams(query: ElectricityOptionQuery): ThirdDataParams? {
        val selection = query.selection
        return when (query.level) {
            ElectricityOptionLevel.Initial -> ThirdDataParams(type = "select", level = "0")
            ElectricityOptionLevel.Buildings -> selection.campus?.value?.takeIf { it.isNotBlank() }
                ?.let { ThirdDataParams(type = "select", level = "1", campus = it) }
            ElectricityOptionLevel.Floors -> {
                val campus = selection.campus?.value?.takeIf { it.isNotBlank() } ?: return null
                val building = selection.building?.value?.takeIf { it.isNotBlank() } ?: return null
                ThirdDataParams(type = "select", level = "2", campus = campus, building = building)
            }
            ElectricityOptionLevel.Rooms -> {
                val campus = selection.campus?.value?.takeIf { it.isNotBlank() } ?: return null
                val building = selection.building?.value?.takeIf { it.isNotBlank() } ?: return null
                val floor = selection.floor?.value?.takeIf { it.isNotBlank() } ?: return null
                ThirdDataParams(
                    type = "select",
                    level = "3",
                    campus = campus,
                    building = building,
                    floor = floor
                )
            }
        }
    }

    private suspend fun thirdDataBody(params: ThirdDataParams): AhuResult<String> {
        var response = CustomCampusCardApi.authorizedCall { params.call() }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return httpFailure(response.code(), "电费请求失败")
        }
        var text = response.body()?.string()
        if (text != null && CustomCampusCardApi.authFailure(envelopeCode(text))) {
            response = retryRaw { params.call() }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return httpFailure(response.code(), "电费请求失败")
            }
            text = response.body()?.string()
        }
        return text?.takeIf { it.isNotBlank() }?.let { AhuResult.Success(it) }
            ?: protocolFailure("电费响应为空")
    }

    private suspend fun <T> retryTyped(
        request: suspend CustomCampusCardApi.() -> Response<T>
    ): Response<T> {
        CustomCampusCardSession.refreshAfterUnauthorized(null)
        return request.invoke(CustomCampusCardApi.API)
    }

    private suspend fun retryRaw(
        request: suspend CustomCampusCardApi.() -> Response<ResponseBody>
    ): Response<ResponseBody> {
        CustomCampusCardSession.refreshAfterUnauthorized(null)
        return request.invoke(CustomCampusCardApi.API)
    }

    private suspend fun ThirdDataParams.call(): Response<ResponseBody> =
        CustomCampusCardApi.API.getThirdData(
            feeItemId = FEE_ITEM_ID,
            type = type,
            level = level,
            campus = campus,
            building = building,
            floor = floor,
            room = room
        )

    private fun envelopeCode(text: String): Int? = runCatching {
        val json = JsonParser.parseString(text)
        if (!json.isJsonObject) null else json.asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
    }.getOrNull()

    private fun isSuccessCode(code: Int): Boolean = code == 200 || code == 0

    private fun httpFailure(code: Int, message: String): AhuResult.Failure =
        if (CustomCampusCardApi.authFailure(code)) {
            AhuResult.Failure(
                AhuError.Unauthorized("自定义校园卡登录已失效，请在设置中检查账号密码")
            )
        } else {
            AhuResult.Failure(AhuError.Server(code, message))
        }

    private fun protocolFailure(detail: String): AhuResult.Failure =
        AhuResult.Failure(AhuError.ProtocolChanged(detail))

    /** 统一线程与网络异常映射，和既有电费适配器保持一致。 */
    private suspend fun <T> guard(block: suspend () -> AhuResult<T>): AhuResult<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SocketTimeoutException) {
                AhuResult.Failure(AhuError.Timeout)
            } catch (_: IOException) {
                AhuResult.Failure(AhuError.Network)
            } catch (error: Exception) {
                AhuResult.Failure(AhuError.Unknown("自定义校园卡请求失败：${error.message}"))
            }
        }

    private data class ThirdDataParams(
        val type: String,
        val level: String,
        val campus: String? = null,
        val building: String? = null,
        val floor: String? = null,
        val room: String? = null
    )

    private val ElectricityOptionLevel.missingMessage: String
        get() = when (this) {
            ElectricityOptionLevel.Initial -> "解析数据失败，未找到电控选项"
            ElectricityOptionLevel.Buildings -> "解析数据失败，未找到楼栋列表"
            ElectricityOptionLevel.Floors -> "解析数据失败，未找到楼层列表"
            ElectricityOptionLevel.Rooms -> "解析数据失败，未找到房间列表"
        }
}
