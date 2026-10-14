package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.plugin.bathroom.Data
import com.ahu.ahutong.plugin.bathroom.BathroomPayment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RepositoryBathroomDepositSourceTest {

    @Test
    fun `protocol obtains the dynamic password map before charging`() = runBlocking {
        val requests = mutableListOf<Map<String, Any>>()
        val subject = BathroomPaymentProtocol { request ->
            requests += request.toMap()
            when (requests.size) {
                1 -> """{"code":200,"success":true,"data":{"orderid":"order-1"},"msg":"操作成功"}"""
                2 -> """{"code":200,"msg":"操作成功","payList":[],"order":{}}"""
                3 -> """{"code":200,"success":true,"data":{"passwordMap":{"dynamic-uuid":"7685349012"}},"msg":"操作成功"}"""
                4 -> """{"code":200,"msg":"操作成功","currentTime":1720000000000}"""
                else -> """{"code":200,"success":true,"data":"receipt-1","msg":"操作成功"}"""
            }
        }

        val result = subject.pay(
            BathroomPayment("竹园/龙河", "0.1", "012345", account())
        )

        assertEquals("receipt-1", result.valueOrNull()?.reference)
        assertEquals(5, requests.size)
        assertEquals("0", requests[0]["paystep"])
        assertTrue(requests[1].isEmpty())
        assertEquals("2", requests[2]["paystep"])
        assertNull(requests[2]["password"])
        assertTrue(requests[3].isEmpty())
        assertEquals("dynamic-uuid", requests[4]["uuid"])
        assertEquals("789453", requests[4]["password"])
    }

    private fun account() = Data(
        projectId = 945,
        projectName = "安大浴室",
        accountId = 1,
        telPhone = "13800000000",
        identifier = null,
        sex = "未知",
        name = null,
        statusId = 0,
        accountMoney = 100,
        accountGivenMoney = 0,
        alias = null,
        tags = null,
        isCard = 0,
        cardStatusId = -1,
        isUseCode = 1,
        cardPhysicalId = null,
        tsmAbstract = "telPhone：13800000000;竹园/龙河浴室",
        myCustomInfo = null,
        message = null
    )
}
