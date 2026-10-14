package com.ahu.ahutong.plugin.program_completion.data.crawler.model.jwxt

import com.google.gson.annotations.SerializedName

/**
 * 培养方案完成情况（教务 program-completion-preview 页 `var model` 的映射）。
 *
 * 设计原则（调研报告 §2/§3.2）：
 * - **不映射 `model.student`**（含身份证号等 PII）；持久化只落映射后的 [ProgramCompletion]，原始 HTML 不落盘。
 * - 学分汇总直接用教务服务端算好的 `completionSummary` / `completeProgress`，客户端不自己聚合
 *   （重修/免修/认定的边角规则客户端算不准，口径以教务为准）。
 * - 状态枚举（resultType 等）是 `{$type:"ResultType",$name:"PASSED"}` 包装对象，统一解出 `$name` 字符串，
 *   未知值原样保留、UI 层归"未完成"，不做强枚举——改版加枚举值时解析层不会炸。
 */
data class ProgramCompletion(
    /** 完成进度展示串，教务原文（如 "34.8%"）。 */
    val progressPercent: String,
    /** 毕业要求总学分。 */
    val requiredCredits: Double,
    val passedCredits: Double,
    val takingCredits: Double,
    val failedCredits: Double,
    val programName: String,
    val programId: Long,
    /** 审核结果是否已发布；false 时 UI 需提示"以教务为准"。 */
    val auditPublished: Boolean,
    val modules: List<CompletionModule>,
    /** 计划外课程（学分认定/替代产生），可为空。 */
    val outerCourses: List<CompletionCourse>
)

data class CompletionModule(
    val nameZh: String,
    val requiredCredits: Double,
    val passedCredits: Double,
    val takingCredits: Double,
    val failedCredits: Double,
    /** finalResultType.$name："PASSED"/"UNPASSED" 等，未知值原样保留。 */
    val result: String?,
    val courses: List<CompletionCourse>,
    /** 子模块（通识选修/学科基础必修等有），结构递归。 */
    val children: List<CompletionModule>
)

data class CompletionCourse(
    val nameZh: String,
    val code: String,
    val credits: Double,
    val compulsory: Boolean,
    /** 成绩展示串（五级制场景非纯数字，优先用它展示）。 */
    val gradeStr: String?,
    val score: Double?,
    val gp: Double?,
    /** resultType.$name：PASSED / TAKING / UNREPAIRED / FAILED / REPAIRED / SKIP ... 未知值原样保留。 */
    val status: String,
    /** 建议修读学期，如 "TERM_2,TERM_5"。 */
    val termsContent: String?,
    val remark: String?
)

/* ---------------- 原始 JSON 映射（仅解析层可见） ---------------- */

/** 教务枚举包装：`{$type:"ResultType",$name:"PASSED"}` → 只取 $name。 */
internal data class TypedName(
    @SerializedName("\$name") val name: String? = null
)

internal data class RawSummary(
    val passedCredits: Double? = null,
    val takingCredits: Double? = null,
    val failedCredits: Double? = null,
    val completeProgress: String? = null,
    val passedCompulsoryCredits: Double? = null,
    val repairedCredits: Double? = null,
    val skipCredits: Double? = null
)

internal data class RawRequire(
    val credits: Double? = null,
    val subModuleNum: Int? = null
)

internal data class RawProgram(
    val id: Long? = null,
    val nameZh: String? = null,
    val grade: String? = null
)

internal data class RawCourse(
    val nameZh: String? = null,
    val code: String? = null,
    val credits: Double? = null,
    val compulsory: Boolean? = null,
    val score: Double? = null,
    val gradeStr: String? = null,
    val gp: Double? = null,
    val resultType: TypedName? = null,
    val finalResultType: TypedName? = null,
    val termsContent: String? = null,
    val remark: String? = null
)

internal data class RawModule(
    val nameZh: String? = null,
    val requireInfo: RawRequire? = null,
    val completionSummary: RawSummary? = null,
    val finalResultType: TypedName? = null,
    val courseList: List<RawCourse>? = null,
    val children: List<RawModule>? = null
)

internal data class RawResult(
    val published: Boolean? = null
)

/** `var model = {...}` 顶层。`student` 字段刻意不声明（PII 不进入内存模型）。 */
internal data class RawProgramCompletionModel(
    val completionSummary: RawSummary? = null,
    val requireInfo: RawRequire? = null,
    val program: RawProgram? = null,
    val moduleList: List<RawModule>? = null,
    val outerCourseList: List<RawCourse>? = null,
    val result: RawResult? = null
)

/* ---------------- Raw → 领域模型 ---------------- */

private fun RawCourse.toDomain(): CompletionCourse = CompletionCourse(
    nameZh = nameZh.orEmpty(),
    code = code.orEmpty(),
    credits = credits ?: 0.0,
    compulsory = compulsory ?: false,
    gradeStr = gradeStr,
    score = score,
    gp = gp,
    status = resultType?.name ?: finalResultType?.name ?: "UNKNOWN",
    termsContent = termsContent,
    remark = remark
)

private fun RawModule.toDomain(): CompletionModule = CompletionModule(
    nameZh = nameZh.orEmpty(),
    requiredCredits = requireInfo?.credits ?: 0.0,
    passedCredits = completionSummary?.passedCredits ?: 0.0,
    takingCredits = completionSummary?.takingCredits ?: 0.0,
    failedCredits = completionSummary?.failedCredits ?: 0.0,
    result = finalResultType?.name,
    courses = courseList.orEmpty().map { it.toDomain() },
    children = children.orEmpty().map { it.toDomain() }
)

internal fun RawProgramCompletionModel.toDomain(): ProgramCompletion = ProgramCompletion(
    progressPercent = completionSummary?.completeProgress.orEmpty(),
    requiredCredits = requireInfo?.credits ?: 0.0,
    passedCredits = completionSummary?.passedCredits ?: 0.0,
    takingCredits = completionSummary?.takingCredits ?: 0.0,
    failedCredits = completionSummary?.failedCredits ?: 0.0,
    programName = program?.nameZh.orEmpty(),
    programId = program?.id ?: 0L,
    auditPublished = result?.published ?: false,
    modules = moduleList.orEmpty().map { it.toDomain() },
    outerCourses = outerCourseList.orEmpty().map { it.toDomain() }
)
