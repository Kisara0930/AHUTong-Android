package com.ahu.ahutong.plugin.chaoxing

/**
 * 学习通作业提醒的排期。
 *
 * 提醒属于后台职责（:app 的 ReminderScheduler，将来归 P4 的 :background），
 * 所以这里是一组命令而不是对调度器的引用——界面与 ViewModel 因此都不持有 Context。
 */
interface ChaoxingReminders {

    /** 登出时撤掉全部学习通提醒。 */
    fun cancelAll()

    /** 登出后按当前（空）数据重新排一次，让系统里的记录与状态一致。 */
    fun scheduleAll()

    /** 数据变化后重排。 */
    fun rescheduleAll()

    /** 调试入口：发一条测试通知。 */
    fun sendTest()
}
