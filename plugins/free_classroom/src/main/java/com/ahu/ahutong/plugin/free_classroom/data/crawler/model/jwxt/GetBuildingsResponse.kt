package com.ahu.ahutong.plugin.free_classroom.data.crawler.model.jwxt

class GetBuildingsResponse : ArrayList<GetBuildingsResponseItem>()

data class GetBuildingsResponseItem(
    val code: String,
    val enabled: Boolean,
    val id: Int,
    val nameEn: Any,
    val nameZh: String
)