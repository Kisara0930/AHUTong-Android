package com.ahu.ahutong.plugin.campuscard

data class PayResponse(
    val code: Int,
    val `data`: String,
    val msg: String,
    val success: Boolean
)