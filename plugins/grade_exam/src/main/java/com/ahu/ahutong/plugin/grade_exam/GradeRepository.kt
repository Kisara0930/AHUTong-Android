package com.ahu.ahutong.plugin.grade_exam

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.data.model.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.plugin.grade_exam.data.crawler.GradeMapper
import com.ahu.ahutong.plugin.grade_exam.data.crawler.model.jwxt.GradeResponse
import com.ahu.ahutong.plugin.grade_exam.data.crawler.utils.GpaRankHtmlParser
import com.ahu.ahutong.plugin.grade_exam.data.grade.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jsoup.Jsoup

/** Full grade/exam implementation belongs to this package; caches are bound to its creating account. */
class GradeRepository : GradeSource, ExamSource {
    private val cache = PluginScope.accountCache()
    private val generation = AccountGeneration.current
    private val gson = Gson()
    override fun usesMockData() = false
    override fun mockRefreshRevisions(): Flow<Long> = emptyFlow()
    override fun cachedProfiles(): List<GradeStudentProfile> = cache.read("profiles", object : TypeToken<List<GradeStudentProfile>>() {}.type) ?: emptyList()
    override fun cachedPerProfileGrades(): Map<String, Grade?> = cache.read("grades", object : TypeToken<Map<String, Grade?>>() {}.type) ?: emptyMap()
    override fun cachedGpaRank(studentId: String): GpaRankInfo? = cache.read("rank.$studentId", GpaRankInfo::class.java)
    override fun saveGpaRank(studentId: String, rank: GpaRankInfo) = cache.save("rank.$studentId", rank)
    override fun cachedExams(): List<Exam> = cache.read("exams", object : TypeToken<List<Exam>>() {}.type) ?: emptyList()
    override fun cachedAt(): Long = cache.string("exams_at")?.toLongOrNull() ?: 0L

    override suspend fun fetchProfiles(): List<GradeStudentProfile> {
        AccountGeneration.requireCurrent(generation)
        cachedProfiles().takeIf { it.isNotEmpty() }?.let { return it }
        val (url, html) = PluginScope.page("/student/for-std/grade/sheet")
        rejectEvaluationGate(html)
        val id = url.substringAfterLast('/').takeIf { it.toLongOrNull() != null }
        val profiles = if (id != null) listOf(GradeStudentProfile(id, "主修", "", "本专业")) else
            Jsoup.parse(html).select(".student-panel-block").mapNotNull { panel ->
                val button = panel.select("button[onclick*=myFunction]").first() ?: return@mapNotNull null
                val studentId = button.attr("value").takeIf { it.toLongOrNull() != null }
                    ?: Regex("\\d+").find(button.attr("onclick"))?.value ?: return@mapNotNull null
                val fields = panel.select("dd")
                GradeStudentProfile(studentId, fields.getOrNull(0)?.text().orEmpty(), fields.getOrNull(1)?.text().orEmpty(), fields.getOrNull(2)?.text().orEmpty())
            }
        check(profiles.isNotEmpty()) { "未能解析教务学生档案，请确认校园账号登录状态" }
        cache.save("profiles", profiles)
        return profiles
    }

    override suspend fun fetchGrade(isRefresh: Boolean): AhuResult<Grade> = result {
        AccountGeneration.requireCurrent(generation)
        val cached = cachedPerProfileGrades().values.filterNotNull()
        if (!isRefresh && cached.isNotEmpty()) return@result GradeMapper.aggregate(cached.flatMap { it.termGradeList.orEmpty() })
        val perProfile = linkedMapOf<String, Grade?>()
        fetchProfiles().forEach { profile ->
            val (_, json) = PluginScope.page("/student/for-std/grade/sheet/info/${profile.id}")
            rejectEvaluationGate(json)
            val response = gson.fromJson(json, GradeResponse::class.java)
            val grade = GradeMapper.fromNativeResponse(response)
            perProfile[profile.id] = grade.takeIf { !it.termGradeList.isNullOrEmpty() }
        }
        cache.save("grades", perProfile)
        GradeMapper.aggregate(perProfile.values.filterNotNull().flatMap { it.termGradeList.orEmpty() })
    }

    override suspend fun fetchGpaRank(studentId: String): AhuResult<GpaRankInfo> = result {
        AccountGeneration.requireCurrent(generation)
        require(studentId.toLongOrNull() != null) { "教务档案标识无效" }
        val (_, html) = PluginScope.page("/student/for-std/grade/sheet/semester-index/$studentId")
        rejectEvaluationGate(html)
        val json = GpaRankHtmlParser.extractModelObject(html).replace("'", "\"")
        gson.fromJson(json, GpaRankInfo::class.java).also { saveGpaRank(studentId, it) }
    }

    override suspend fun fetchExams(isRefresh: Boolean, studentId: String, studentName: String): AhuResult<List<Exam>> = result {
        AccountGeneration.requireCurrent(generation)
        if (!isRefresh && cachedExams().isNotEmpty()) return@result cachedExams()
        val (_, html) = PluginScope.page("/student/for-std/exam-arrange")
        val rendered = ExamHtmlParser.parseExamTableHtml(html)
        val exams = if (rendered.isNotEmpty()) rendered else parseLegacyExams(html)
        cache.save("exams", exams)
        cache.put("exams_at", System.currentTimeMillis().toString())
        exams
    }

    private fun parseLegacyExams(html: String): List<Exam> {
        val match = Regex("(?s)studentExamInfoVms\\s*=\\s*(\\[.*?]);").find(html) ?: return emptyList()
        return JsonParser.parseString(match.groupValues[1].replace("'", "\"")).asJsonArray.map { element ->
            val value = element.asJsonObject
            fun text(key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val course = value.getAsJsonObject("course")?.get("nameZh")?.asString.orEmpty()
            val type = value.getAsJsonObject("examType")?.get("nameZh")?.asString.orEmpty()
            val campus = value.getAsJsonObject("requiredCampus")?.get("nameZh")?.asString.orEmpty()
            Exam().apply {
                setCourse(if (type.isBlank()) course else "$course($type)")
                setTime(text("examTime")); setSeatNum(text("seatNo"))
                setLocation(listOf(campus, text("room")).filter { it.isNotBlank() }.joinToString("-"))
                setFinished(value.get("finished")?.asBoolean ?: false)
            }
        }
    }

    private fun rejectEvaluationGate(payload: String) {
        check(!GradeEvaluationGate.isRequiredPayload(payload)) { GradeEvaluationGate.MESSAGE }
    }
    private suspend fun <T> result(block: suspend () -> T): AhuResult<T> = try {
        PluginScope.requireUndergraduate()
        val value = block()
        AccountGeneration.requireCurrent(generation)
        AhuResult.Success(value)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { AhuResult.Failure(failure.toPluginError()) }
}
