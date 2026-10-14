package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.storage.BinaryCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals

class BinaryCodecTest {
    @Test
    fun vectorsRoundTripWithoutChangingTrainingInput() {
        val floats = floatArrayOf(-1f, 0f, 0.25f, Float.MAX_VALUE)
        val booleans = booleanArrayOf(true, false, true)
        assertContentEquals(floats, BinaryCodec.floats(BinaryCodec.floats(floats)))
        assertContentEquals(booleans, BinaryCodec.booleans(BinaryCodec.booleans(booleans)))
    }
}
