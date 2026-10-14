package com.ahu.ahutong.plugin.chaoxing

/**
 * 学习通本地数据的读写：作业、课程、进度、提醒设置、自定义事件与三个显示开关。
 *
 * 这些数据属于「可以重新同步得到」的一类，因此实现直接落在本机存储上
 * （:app 的适配器，委托给 Store 的静态读写）。
 */
interface ChaoxingStore {

    fun works(): List<Work>

    fun courses(): List<Course>

    fun courseProgress(): List<CourseProgress>

    fun lastSync(): Long

    fun remindSetting(): RemindSetting

    fun customEvents(): List<CustomEvent>

    fun showDone(): Boolean

    fun doneGray(): Boolean

    fun showEmptyCourses(): Boolean

    fun saveShowDone(value: Boolean)

    fun saveDoneGray(value: Boolean)

    fun saveShowEmptyCourses(value: Boolean)

    fun saveLastSync(at: Long)

    fun saveRemindSetting(setting: RemindSetting)

    fun saveCustomEvents(events: List<CustomEvent>)

    /** 登录数据（会话与缓存）整批清掉。 */
    fun clearLoginData()
}
