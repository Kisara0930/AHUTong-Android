package com.ahu.ahutong.plugin.grade_exam.data.grade

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.MockDataSignals
import com.ahu.ahutong.data.model.GpaRankInfo
import com.ahu.ahutong.data.model.Grade
import com.ahu.ahutong.data.model.GradeStudentProfile

/**
 * 成绩数据的唯一入口。
 *
 * 界面问的是：有没有缓存的成绩、绩点排名是多少、这学期有哪些档案、要不要拉一次。
 * 协议（教务成绩页解析）与缓存分层（内存 / 磁盘 / 按档案分箱）都留在实现里
 * （:app 的 AHURepository 与 AHUCache）。
 *
 * 接口里不出现 Context 与协议类型，因此 feature 的 ViewModel 可以在 JVM 上被 fake 驱动。
 */
interface GradeSource : MockDataSignals {

    /** 缓存里的学生档案。 */
    fun cachedProfiles(): List<GradeStudentProfile>

    /** 按档案缓存的成绩：档案 id → 成绩（null 表示该档案确实没有成绩）。 */
    fun cachedPerProfileGrades(): Map<String, Grade?>

    /** 某个档案缓存下来的绩点排名。 */
    fun cachedGpaRank(studentId: String): GpaRankInfo?

    /** 拉取成绩；[isRefresh] 为真时强制绕过缓存。 */
    suspend fun fetchGrade(isRefresh: Boolean): AhuResult<Grade>

    /** 拉取学生档案（多学号学生的每个专业一条）。 */
    suspend fun fetchProfiles(): List<GradeStudentProfile>

    /** 拉取某个档案的绩点排名。 */
    suspend fun fetchGpaRank(studentId: String): AhuResult<GpaRankInfo>

    /** 保存绩点排名（下次打开先用缓存）。 */
    fun saveGpaRank(studentId: String, rank: GpaRankInfo)
}
