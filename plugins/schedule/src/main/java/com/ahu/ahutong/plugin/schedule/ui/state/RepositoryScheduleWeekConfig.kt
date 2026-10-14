package com.ahu.ahutong.plugin.schedule.ui.state

import com.ahu.ahutong.plugin.schedule.ScheduleCache
import com.ahu.ahutong.plugin.schedule.data.schedule.ConfigSource
import com.ahu.ahutong.plugin.schedule.data.schedule.CurrentWeekResolver
import com.ahu.ahutong.plugin.schedule.data.schedule.ResolvedConfig
import com.ahu.ahutong.plugin.schedule.data.schedule.ScheduleWeekConfig
import com.ahu.ahutong.plugin.schedule.data.schedule.SemesterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ScheduleWeekConfig] 的生产实现：转给 CurrentWeekResolver（问教务与校历）与 AHUCache（落盘）。
 *
 * 适配器负责把实现侧的形状翻成端口的形状（CurrentWeekResolver.Source → ConfigSource），
 * 于是课表的 ViewModel 既不需要认识教务协议，也不需要认识缓存的键名与观测日。
 */
class RepositoryScheduleWeekConfig(private val resolver: CurrentWeekResolver = CurrentWeekResolver(), private val cache: ScheduleCache = ScheduleCache.current()) : ScheduleWeekConfig {

    override fun cachedSemesterKey(): SemesterKey? =
        resolver.getCachedSemesterKey()?.let {
            SemesterKey(raw = it.raw, schoolYear = it.schoolYear, schoolTerm = it.schoolTerm)
        }

    override fun storedSchoolYear(): String? = cache.getSchoolYear()

    override fun currentWeekDay(): Int = resolver.getCurrentWeekDay()

    override fun buildSemesterKey(schoolYear: String, schoolTerm: String): String =
        resolver.buildSemesterKey(schoolYear, schoolTerm)

    override fun isShowAllCourse(): Boolean = cache.isShowAllCourse()

    /** 调度器由实现决定：这两个方法原先由调用方包在 Dispatchers.IO 里。 */
    override suspend fun resolveLocalFirst(): ResolvedConfig = withContext(Dispatchers.IO) {
        resolver.resolveLocalFirst().toPortShape()
    }

    override suspend fun syncRemote(): ResolvedConfig? = withContext(Dispatchers.IO) {
        resolver.syncRemoteConfig()?.toPortShape()
    }

    override suspend fun saveSchoolYearAndTerm(schoolYear: String, semesterKey: String) {
        cache.saveSchoolYear(schoolYear)
        cache.saveSchoolTerm(semesterKey)
    }

    override suspend fun saveTermPosition(
        schoolYear: String,
        schoolTerm: String,
        startTime: String,
        isInSemester: Boolean,
        observedOn: String
    ) {
        cache.saveSchoolTermStartTime(schoolYear, schoolTerm, startTime)
        cache.saveSchoolTermInSemester(
            schoolYear = schoolYear,
            schoolTerm = schoolTerm,
            isInSemester = isInSemester,
            observedOn = observedOn
        )
    }

    private fun CurrentWeekResolver.ResolvedConfig.toPortShape() = ResolvedConfig(
        config = config,
        source = when (source) {
            CurrentWeekResolver.Source.LOCAL -> ConfigSource.LOCAL
            CurrentWeekResolver.Source.REMOTE -> ConfigSource.REMOTE
            CurrentWeekResolver.Source.DEFAULT -> ConfigSource.DEFAULT
        }
    )
}
