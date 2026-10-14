package com.ahu.ahutong.plugin.chaoxing

/**
 * 后台提醒用得到的「学习通快照 + 记账」。
 *
 * 提醒不许登录、不许碰凭据（计划 P4 的验收标准），而 [Store] 是学习通的全量入口：Cookie、账号密码、
 * 课程、作业、自定义日程、提醒设置与记账都在里面。后台只认识这个窄接口，门禁 R28 同时禁止它 import
 * [Store]——于是「后台能读写什么」由类型决定，而不是靠约定。
 *
 * 成员沿用 [Store] 的原名：这个接口就是它的一个窄视图，不额外改名。
 */
interface ChaoxingReminderStore {

    /** 缓存里的作业：提醒只读它判断截止时间。 */
    fun getWorks(): List<Work>

    /** 用户自建日程。 */
    fun getCustomEvents(): List<CustomEvent>

    /** 提醒开关与提前量。 */
    fun getRemindSetting(): RemindSetting

    /** 已经排过的提醒（键 -> 1）：后台自己的记账。 */
    fun getRemindedMap(): Map<String, Int>

    /** 写回记账：后台唯一允许的写。 */
    fun saveRemindedMap(map: Map<String, Int>)

    /** 本机有没有学习通登录态：没有就不排提醒。 */
    fun hasCookie(): Boolean
}
