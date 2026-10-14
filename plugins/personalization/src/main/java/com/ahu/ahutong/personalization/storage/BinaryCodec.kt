package com.ahu.ahutong.personalization.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryCodec {
    fun floats(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    fun floats(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    fun booleans(values: BooleanArray): ByteArray = ByteArray(values.size) { if (values[it]) 1 else 0 }

    fun booleans(bytes: ByteArray): BooleanArray = BooleanArray(bytes.size) { bytes[it].toInt() != 0 }
}
