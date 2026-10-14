package com.ahu.ahutong.plugin.electricity

import com.ahu.ahutong.plugin.electricity.generateNonce
import com.ahu.ahutong.plugin.electricity.getTimestamp
import com.ahu.ahutong.plugin.electricity.sha256
import okhttp3.FormBody

private const val PAYMENT_APP_ID = "56321"
private const val PAYMENT_SECRET_KEY = "0osTIhce7uPvDKHz6aa67bhCukaKoYl4"

internal fun signedPaymentParams(
    params: Map<String, Any>,
    timestamp: String = getTimestamp(),
    nonce: String = generateNonce()
): Map<String, Any> {
    val signedParams = linkedMapOf<String, Any>().apply {
        putAll(params)
        put("APP_ID", PAYMENT_APP_ID)
        put("TIMESTAMP", timestamp)
        put("SIGN_TYPE", "SHA256")
        put("NONCE", nonce)
    }
    val signaturePayload = signedParams.entries
        .asSequence()
        .filter { (_, value) -> value.toString().isNotEmpty() }
        .sortedBy { (key, _) -> key }
        .joinToString("&") { (key, value) -> "$key=$value" }

    signedParams["SIGN"] = sha256("$signaturePayload&SECRET_KEY=$PAYMENT_SECRET_KEY").uppercase()
    return signedParams
}

/** 支付签名与表单编码的唯一入口；feature 只提供业务字段，不接触协议密钥。 */
fun buildSignedPaymentFormBody(params: Map<String, String>): FormBody =
    buildSignedPaymentFormBody(params, getTimestamp(), generateNonce())

internal fun buildSignedPaymentFormBody(
    params: Map<String, String>,
    timestamp: String,
    nonce: String
): FormBody = FormBody.Builder().apply {
    signedPaymentParams(params, timestamp, nonce).forEach { (key, value) ->
        add(key, value.toString())
    }
}.build()
