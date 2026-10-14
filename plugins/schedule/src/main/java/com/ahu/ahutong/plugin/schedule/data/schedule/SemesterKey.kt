package com.ahu.ahutong.plugin.schedule.data.schedule

/**
 * 一个学期的稳定标识：原始键（形如 2024-2025-1）、学年与学期号。
 *
 * 原先挂在 CurrentWeekResolver 内部；它同时被界面、小组件与提醒调度使用，所以和端口一起
 * 待在数据模块里，而不是藏在某个实现的伴生对象里。
 */
data class SemesterKey(
    val raw: String,
    val schoolYear: String,
    val schoolTerm: String
)
