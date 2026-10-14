package com.ahu.ahutong.plugin.schedule

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.model.Course
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.plugin.schedule.data.crawler.model.jwxt.CourseTable
import com.ahu.ahutong.plugin.schedule.data.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ScheduleRepository(private val cache: ScheduleCache = ScheduleCache.current(), private val onChanged: () -> Unit = {}) : ScheduleSource {
    private val generation = AccountGeneration.current
    private val mutex = Mutex()
    override fun usesMockData() = false
    override fun cached(): List<Course>? = cache.getSchoolTerm()?.let(cache::getSchedule)
    override fun fetchedAt(): Long? = cache.getSchoolTerm()?.let(cache::getScheduleFetchedAt)
    override suspend fun fetch(isRefresh: Boolean): AhuResult<List<Course>> {
        if (!isRefresh) cached()?.let { return AhuResult.Success(it) }
        return when (val result = refreshCache()) {
            is AhuResult.Success -> AhuResult.Success(result.value.schedule)
            is AhuResult.Failure -> result
        }
    }
    override suspend fun refreshCache(): AhuResult<ScheduleRefreshResult> = result {
        mutex.withLock {
            val api = ScheduleApi.current()
            val semester = fetchCurrentSemester(api)
            val courses = api.getCourse(semester.id, semester.id).courses()
            AccountGeneration.requireCurrent(generation)
            val changed = ScheduleSnapshotComparator.hasChanged(cache.getSchedule(semester.name), courses)
            val time = System.currentTimeMillis()
            cache.saveSchoolTerm(semester.name)
            semester.name.split('-').takeIf { it.size >= 3 }?.let { cache.saveSchoolYear("${it[0]}-${it[1]}") }
            if (changed) cache.saveSchedule(semester.name, courses)
            cache.saveScheduleFetchedAt(semester.name, time)
            onChanged()
            ScheduleRefreshResult(courses, changed, time)
        }
    }
    override suspend fun next(isRefresh: Boolean): AhuResult<List<Course>> = result {
        if (!isRefresh) cache.getNextSchedule()?.let { return@result it }
        val api = ScheduleApi.current()
        val semester = fetchCurrentSemester(api)
        val courses = api.getCourse(semester.id + 20, semester.id).courses()
        AccountGeneration.requireCurrent(generation)
        cache.saveNextSchedule(courses)
        courses
    }
    private fun CourseTable.courses(): List<Course> = studentTableVms.firstOrNull()?.activities.orEmpty().mapNotNull { item ->
        val weeks = item.weekIndexes.sorted()
        if (weeks.isEmpty()) null else Course().apply {
            name = item.courseName; setStartWeek(weeks.first().toString()); setEndWeek(weeks.last().toString())
            setLength((item.endUnit - item.startUnit + 1).toString()); setWeekday(item.weekday.toString())
            setStartTime(item.startUnit.toString()); location = item.room ?: "未知"
            teacher = item.teacherNames.joinToString(", "); weekIndexes = weeks; courseId = item.lessonId.toString()
        }
    }
    private suspend fun <T> result(block: suspend () -> T): AhuResult<T> = withContext(Dispatchers.IO) {
        try { AccountGeneration.requireCurrent(generation); AhuResult.Success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { AhuResult.Failure(error.toPluginError()) }
    }
}
