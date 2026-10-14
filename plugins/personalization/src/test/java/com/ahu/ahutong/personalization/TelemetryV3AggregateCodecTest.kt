package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.telemetry.StoredTelemetryV3Aggregate
import com.ahu.ahutong.personalization.telemetry.TelemetryV3AggregateCodec
import com.ahu.ahutong.personalization.telemetry.TelemetryV3Task
import com.ahu.ahutong.personalization.telemetry.V3DeliveryAggregate
import com.ahu.ahutong.personalization.telemetry.V3DeliveryLaneAggregate
import com.ahu.ahutong.personalization.telemetry.V3NamedCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryV3AggregateCodecTest {
    private val codec = TelemetryV3AggregateCodec()

    @Test
    fun `round trips nested V3 delivery aggregate with concrete element types`() {
        val original = StoredTelemetryV3Aggregate(
            delivery = V3DeliveryAggregate(
                lanes = listOf(
                    V3DeliveryLaneAggregate(
                        lane = "TARGETED",
                        opportunities = 2,
                        blocked = listOf(V3NamedCount("INTERVAL", 1)),
                        latencyBuckets = listOf(V3NamedCount("LT_1S", 1))
                    )
                )
            )
        )

        val json = codec.encode(original)
        val decoded = codec.decode(json, TelemetryV3Task.DELIVERY)

        assertEquals(original, decoded)
        assertEquals(V3DeliveryLaneAggregate::class.java, decoded!!.delivery!!.lanes.single().javaClass)
        assertEquals(V3NamedCount::class.java, decoded.delivery!!.lanes.single().blocked.single().javaClass)
    }

    @Test
    fun `rejects aggregate written without stable storage marker`() {
        val legacyReleaseJson = """{"d":{"a":[{"a":"TARGETED","b":1}]}}"""

        assertNull(codec.decode(legacyReleaseJson, TelemetryV3Task.DELIVERY))
    }

    @Test
    fun `rejects aggregate when stored payload does not match task`() {
        val deliveryJson = codec.encode(
            StoredTelemetryV3Aggregate(delivery = V3DeliveryAggregate())
        )

        assertNull(codec.decode(deliveryJson, TelemetryV3Task.NEXT_ACTION))
    }
}
