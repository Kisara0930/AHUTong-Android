package com.ahu.ahutong.plugin.bathroom

data class PayResponse(
    val code: Int,
    val `data`: String,
    val msg: String,
    val success: Boolean
)