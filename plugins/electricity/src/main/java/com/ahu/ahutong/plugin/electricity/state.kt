package com.ahu.ahutong.plugin.electricity

sealed class PayState {
    object Idle : PayState()
    object InProgress : PayState()
    data class Failed(val message: String) : PayState()
    data class Succeeded(val message: String) : PayState()
}