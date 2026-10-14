package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.data.model.CardRechargeBank
import com.ahu.ahutong.data.security.SecureBoxStore
import com.ahu.ahutong.data.session.AccountGeneration

class CampusCardCache(profile: String) {
    private val box = SecureBoxStore.userBox(profile)
    private val generation = AccountGeneration.current
    fun getCardRechargeBank(): CardRechargeBank? {
        CardRechargeBank.fromStorage(SecureBoxStore.get(box, "card_recharge_bank"))?.let { return it }
        return SecureBoxStore.get(box, "cmb_card_recharge_preferred")?.toBooleanStrictOrNull()?.let {
            if (it) CardRechargeBank.CHINA_MERCHANTS_BANK else CardRechargeBank.AGRICULTURAL_BANK
        }?.also(::setCardRechargeBank)
    }
    fun setCardRechargeBank(value: CardRechargeBank) {
        AccountGeneration.requireCurrent(generation)
        SecureBoxStore.put(box, "card_recharge_bank", value.storageValue)
    }
    fun balance(): Double? = SecureBoxStore.get(box, "card_balance")?.toDoubleOrNull()
    fun saveBalance(value: Double) { AccountGeneration.requireCurrent(generation); SecureBoxStore.put(box, "card_balance", value.toString()) }
    fun showQr(): Boolean = SecureBoxStore.get(box, "campus_card_show_qr")?.toBooleanStrictOrNull() ?: false
    fun saveShowQr(value: Boolean) { AccountGeneration.requireCurrent(generation); SecureBoxStore.put(box, "campus_card_show_qr", value.toString()) }
}
