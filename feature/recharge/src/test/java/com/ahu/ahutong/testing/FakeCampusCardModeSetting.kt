package com.ahu.ahutong.testing

import com.ahu.ahutong.core.storage.CampusCardModeSetting
import com.ahu.ahutong.data.model.CampusCardProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [CampusCardModeSetting] 的 fake：默认原有数据源，需要时切到自定义。 */
class FakeCampusCardModeSetting(
    initial: CampusCardProvider = CampusCardProvider.AHU
) : CampusCardModeSetting {

    val provider = MutableStateFlow(initial)

    override val campusCardProvider: Flow<CampusCardProvider> = provider
}
