package com.ahu.ahutong.plugin.program_completion.data.crawler.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 培养方案完成情况解析器单测。
 * fixture 按调研报告 §2 实测字段结构合成（2026-10-01 抓包）：
 * 单引号 JS 字面量、`{$type,$name}` 枚举包装、子模块递归、三种课程状态。
 * 真页 487KB 含 PII（model.student 有身份证号）不入库——用合成数据覆盖同构结构。
 */
class ProgramCompletionHtmlParserTest {

    /** 最小但结构完整的单引号字面量页：2 模块（其一含子模块）、3 种课程状态、计划外课程。 */
    private fun page(modelLiteral: String): String = """
        <html><head><title>培养方案完成情况</title></head><body>
        <script>
        $modelLiteral
        </script>
        </body></html>
    """.trimIndent()

    private val standardModel = """
        var model = {
            'completionSummary':{'passedCredits':56,'takingCredits':26.5,'failedCredits':105,'completeProgress':'34.8%','repairedCredits':2,'skipCredits':0},
            'requireInfo':{'credits':161,'subModuleNum':7},
            'program':{'id':6718,'nameZh':'2025级电子信息工程专业人才培养方案(主修)','grade':'2025'},
            'result':{'id':900,'published':false},
            'moduleList':[
                {'nameZh':'思想政治理论','moduleId':11,
                 'requireInfo':{'credits':18,'subModuleNum':0},
                 'completionSummary':{'passedCredits':8,'takingCredits':4,'failedCredits':6},
                 'finalResultType':{${'$'}type:'FinalResultType',${'$'}name:'UNPASSED'},
                 'courseList':[
                    {'nameZh':'思想道德与法治','code':'GG61014','credits':3,'compulsory':true,
                     'score':91,'gradeStr':'91','gp':4.1,
                     'resultType':{${'$'}type:'ResultType',${'$'}name:'PASSED'},
                     'finalResultType':{${'$'}type:'ResultType',${'$'}name:'PASSED'},
                     'termsContent':'TERM_1','remark':null},
                    {'nameZh':'马克思主义基本原理','code':'GG61012','credits':3,'compulsory':true,
                     'score':null,'gradeStr':null,'gp':null,
                     'resultType':{${'$'}type:'ResultType',${'$'}name:'TAKING'},
                     'finalResultType':{${'$'}type:'ResultType',${'$'}name:'TAKING'},
                     'termsContent':'TERM_2,TERM_3','remark':undefined},
                    {'nameZh':'形势与政策','code':'GG61001','credits':2,'compulsory':true,
                     'score':null,'gradeStr':null,'gp':null,
                     'resultType':{${'$'}type:'ResultType',${'$'}name:'UNREPAIRED'},
                     'finalResultType':{${'$'}type:'ResultType',${'$'}name:'UNREPAIRED'},
                     'termsContent':null,'remark':null}
                 ],
                 'children':[]},
                {'nameZh':'通识选修','moduleId':12,
                 'requireInfo':{'credits':10,'subModuleNum':1},
                 'completionSummary':{'passedCredits':4,'takingCredits':0,'failedCredits':6},
                 'finalResultType':{${'$'}type:'FinalResultType',${'$'}name:'UNPASSED'},
                 'courseList':[],
                 'children':[
                    {'nameZh':'公共艺术类课程','moduleId':121,
                     'requireInfo':{'credits':2,'subModuleNum':0},
                     'completionSummary':{'passedCredits':0,'takingCredits':0,'failedCredits':2},
                     'finalResultType':{${'$'}type:'FinalResultType',${'$'}name:'UNPASSED'},
                     'courseList':[
                        {'nameZh':'艺术导论','code':'TS0001','credits':2,'compulsory':false,
                         'score':null,'gradeStr':null,'gp':null,
                         'resultType':{${'$'}type:'ResultType',${'$'}name:'UNREPAIRED'},
                         'finalResultType':null,'termsContent':'TERM_4','remark':'建议大一大二修读'}
                     ],
                     'children':[]}
                 ]}
            ],
            'outerCourseList':[
                {'nameZh':'军事训练','code':'JW0001','credits':2,'compulsory':true,
                 'score':null,'gradeStr':'合格','gp':null,
                 'resultType':{${'$'}type:'ResultType',${'$'}name:'PASSED'},
                 'finalResultType':null,'termsContent':null,'remark':'学分认定'}
            ],
            'outerCompletionSummary':{'passedCredits':2,'takingCredits':0,'failedCredits':0},
            'student':{'idNumber':'SHOULD_NOT_BE_MAPPED','name':'占位'}
        };
    """.trimIndent()

    @Test
    fun parsesFullModelIntoDomain() {
        val result = ProgramCompletionHtmlParser.parse(page(standardModel))

        assertEquals("34.8%", result.progressPercent)
        assertEquals(161.0, result.requiredCredits)
        assertEquals(56.0, result.passedCredits)
        assertEquals(26.5, result.takingCredits)
        assertEquals(105.0, result.failedCredits)
        assertEquals(6718L, result.programId)
        assertEquals("2025级电子信息工程专业人才培养方案(主修)", result.programName)
        assertEquals(false, result.auditPublished)

        assertEquals(2, result.modules.size)
        val thought = result.modules[0]
        assertEquals("思想政治理论", thought.nameZh)
        assertEquals(18.0, thought.requiredCredits)
        assertEquals(8.0, thought.passedCredits)
        assertEquals("UNPASSED", thought.result)
        assertEquals(3, thought.courses.size)

        // 三种状态各≥1（调研报告断言点）
        val statuses = thought.courses.map { it.status }.toSet()
        assertTrue(statuses.containsAll(setOf("PASSED", "TAKING", "UNREPAIRED")))
        val passed = thought.courses.first { it.status == "PASSED" }
        assertEquals(91.0, passed.score)
        assertEquals(4.1, passed.gp)
        assertEquals("91", passed.gradeStr)
        assertEquals("TERM_1", passed.termsContent)
        // undefined → null
        assertEquals(null, thought.courses.first { it.code == "GG61012" }.remark)
        assertEquals("TERM_2,TERM_3", thought.courses.first { it.code == "GG61012" }.termsContent)

        // 子模块递归
        val elective = result.modules[1]
        assertEquals(1, elective.children.size)
        val art = elective.children[0]
        assertEquals("公共艺术类课程", art.nameZh)
        assertEquals(2.0, art.requiredCredits)
        assertEquals(1, art.courses.size)
        assertEquals("艺术导论", art.courses[0].nameZh)
        assertEquals(false, art.courses[0].compulsory)

        // 计划外课程
        assertEquals(1, result.outerCourses.size)
        assertEquals("合格", result.outerCourses[0].gradeStr)
        assertEquals("学分认定", result.outerCourses[0].remark)
    }

    @Test
    fun toleratesVariantWithoutSpaces() {
        // 变体：var model={...}（无空格）
        val compact = standardModel.replace("var model = {", "var model={")
        val result = ProgramCompletionHtmlParser.parse(page(compact))
        assertEquals(161.0, result.requiredCredits)
        assertEquals(2, result.modules.size)
    }

    @Test
    fun handlesQuotesAndEscapesInsideStrings() {
        // 单引号串内含 \' 转义与裸双引号：规整后必须是合法 JSON 且内容不丢
        val tricky = """
            var model = {
                'completionSummary':{'passedCredits':1,'takingCredits':0,'failedCredits':0,'completeProgress':'1.0%'},
                'requireInfo':{'credits':100,'subModuleNum':1},
                'program':{'id':1,'nameZh':'It\'s a "test" program','grade':'2025'},
                'result':{'published':true},
                'moduleList':[
                    {'nameZh':'模块A','requireInfo':{'credits':100,'subModuleNum':0},
                     'completionSummary':{'passedCredits':1,'takingCredits':0,'failedCredits':99},
                     'finalResultType':null,
                     'courseList':[{'nameZh':'课程','code':'A1','credits':1,'compulsory':true,
                        'score':null,'gradeStr':null,'gp':null,
                        'resultType':{${'$'}name:'PASSED'},'finalResultType':null,
                        'termsContent':null,'remark':'老师说 "OK" 就行'}],
                     'children':[]}
                ],
                'outerCourseList':[]
            };
        """.trimIndent()
        val result = ProgramCompletionHtmlParser.parse(page(tricky))
        assertEquals("It's a \"test\" program", result.programName)
        assertEquals(true, result.auditPublished)
        assertEquals("老师说 \"OK\" 就行", result.modules[0].courses[0].remark)
    }

    @Test
    fun missingModelThrowsParseException() {
        val e = assertFailsWith<ProgramCompletionHtmlParser.ParseException> {
            ProgramCompletionHtmlParser.parse("<html><body>登录页或无数据</body></html>")
        }
        assertTrue(e.message!!.contains("var model"))
    }

    @Test
    fun emptyStructureIsSentinelError() {
        // 结构在但关键字段全空 → 视为改版，抛错而非展示全零
        val emptyModel = """
            var model = {'completionSummary':{},'requireInfo':{},'moduleList':[],'outerCourseList':[]};
        """.trimIndent()
        assertFailsWith<ProgramCompletionHtmlParser.ParseException> {
            ProgramCompletionHtmlParser.parse(page(emptyModel))
        }
    }

    @Test
    fun unclosedLiteralThrows() {
        val broken = "var model = {'completionSummary':{'passedCredits':1"
        assertFailsWith<ProgramCompletionHtmlParser.ParseException> {
            ProgramCompletionHtmlParser.parse(page(broken))
        }
    }
}
