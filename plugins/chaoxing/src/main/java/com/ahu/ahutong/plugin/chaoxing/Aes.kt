package com.ahu.ahutong.plugin.chaoxing

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Aes {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    const val CX_AES_KEY = "u2oh6Vu^HWe4_AES"

    fun encrypt(message: String, key: String = CX_AES_KEY): String {
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, ALGORITHM)
        val ivSpec = IvParameterSpec(keyBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}