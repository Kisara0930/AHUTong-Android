package com.ahu.ahutong.plugin.bathroom

data class BathroomPayment(val bathroom: String, val amount: String, val password: String, val account: Data)
data class RechargeReceipt(val reference: String)
