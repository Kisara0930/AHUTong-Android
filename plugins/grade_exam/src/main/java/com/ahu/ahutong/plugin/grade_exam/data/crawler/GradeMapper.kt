package com.ahu.ahutong.plugin.grade_exam.data.crawler

import com.ahu.ahutong.plugin.grade_exam.data.crawler.model.jwxt.GradeResponse
import com.ahu.ahutong.data.model.Grade

/**
 * 成绩映射的唯一实现：上游成绩数据 → 领域 Grade。
 *
 * 为什么要有这个对象：总学分 / 总学分绩点 / 加权平均学分绩点这三段算术原先在四个地方
 * 各写了一遍（爬虫网关的单档案构建、爬虫网关的多档案合并、SDK 网关的响应映射、
 * SDK 网关的多档案合并）。四份实现意味着"两个网关给出同样的成绩"只是巧合而不是结构保证——
 * 校方字段语义一变，只会改到其中一处，另一处静默漂移，而成绩是学生最不能接受出错的页面。
 *
 * 收口范围只包括**四份完全相同的聚合算术**。每学期的构建逻辑留在各自的适配器里：
 * 爬虫侧与 SDK 侧对缺字段的处理本来就不同（前者对学分与学期 id 直接取值，后者回落为
 * 0.0 / 0），统一它们属于行为变更，不在本次范围内。
 *
 * 纯函数、不碰 Android 与存储，因此可以被 JVM 单测用 fixture 直接驱动
 * （这是 SDK 网关唯一能在无设备环境下被覆盖的部分，见 GradeMapperContractTest）。
 */
internal object GradeMapper {

    /**
     * 各学期汇总 → 领域 Grade。
     *
     * 公式与迁移前逐字对应：空输入返回 "0.0" 而不是抛异常；总绩点为学期加权和，
     * 平均学分绩点为加权和 ÷ 总学分并保留两位小数。
     */
    fun aggregate(terms: List<Grade.TermGradeListBean>): Grade {
        val totalCredit = terms.sumOf { it.termTotalCredit?.toDoubleOrNull() ?: 0.0 }
        val weightedGradePointSum = terms.sumOf {
            val termAverage = it.termGradePointAverage?.toDoubleOrNull() ?: 0.0
            val termCredit = it.termTotalCredit?.toDoubleOrNull() ?: 0.0
            termAverage * termCredit
        }

        val grade = Grade()
        grade.totalCredit = totalCredit.toString()
        grade.totalGradePoint = weightedGradePointSum.toString()
        grade.totalGradePointAverage = if (totalCredit > 0) {
            "%.2f".format(weightedGradePointSum / totalCredit)
        } else {
            "0.0"
        }
        grade.termGradeList = terms
        return grade
    }

    /**
     * SDK/原生服务返回的成绩响应 → 领域 Grade。
     *
     * 与迁移前的 SDK 网关实现行为一致：同一学期名只保留一份（后者覆盖前者），
     * 学期名解析不出"学年-学期"（形如 2023-2024-1）就跳过该学期，缺字段一律回落为 0 / 空串。
     */
    fun fromNativeResponse(response: GradeResponse): Grade {
        val termsByName = hashMapOf<String, Grade.TermGradeListBean>()

        response.semesterId2studentGrades?.values?.forEach { courseGrades ->
            var termName: String? = null
            val courses = mutableListOf<Grade.TermGradeListBean.GradeListBean>()

            courseGrades.forEach { course ->
                termName = termName ?: course.semesterName
                val courseDto = Grade.TermGradeListBean.GradeListBean()
                courseDto.course = course.courseName ?: ""
                courseDto.credit = (course.credits ?: 0.0).toString()
                courseDto.grade = course.gaGrade ?: ""
                courseDto.gradePoint = (course.gp ?: 0.0).toString()
                courseDto.courseNature = course.courseType ?: ""
                courseDto.courseNum = course.courseCode ?: ""
                courseDto.gradeDetail = course.gradeDetail ?: ""
                courseDto.semesterId = course.semesterId ?: 0
                courses.add(courseDto)
            }

            val name = termName ?: return@forEach
            val nameParts = name.split("-")
            if (nameParts.size < 3) return@forEach

            val term = Grade.TermGradeListBean()
            term.gradeList = courses
            term.term = nameParts[2]
            term.schoolYear = nameParts[0] + "-" + nameParts[1]
            term.termGradePoint = courses.sumOf { it.grade?.toDoubleOrNull() ?: 0.0 }.toString()
            val termCredit = courses.sumOf { it.credit?.toDoubleOrNull() ?: 0.0 }
            term.termTotalCredit = termCredit.toString()
            val termWeighted = courses.sumOf {
                (it.gradePoint?.toDoubleOrNull() ?: 0.0) * (it.credit?.toDoubleOrNull() ?: 0.0)
            }
            term.termGradePointAverage = if (termCredit > 0) {
                "%.2f".format(termWeighted / termCredit)
            } else {
                "0.0"
            }
            termsByName[name] = term
        }

        return aggregate(termsByName.values.toList())
    }
}
