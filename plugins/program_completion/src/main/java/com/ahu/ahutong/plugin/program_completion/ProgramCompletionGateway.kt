package com.ahu.ahutong.plugin.program_completion

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.program_completion.data.crawler.model.jwxt.ProgramCompletion
import com.ahu.ahutong.plugin.program_completion.data.crawler.utils.ProgramCompletionHtmlParser
import com.ahu.ahutong.data.session.AccountGeneration
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

/** The program plugin owns both protocol parsing and account-scoped cached completion data. */
class ProgramCompletionGateway {
    private val cache = PluginScope.accountCache()
    private data class Cached(val fetchedAt: Long, val data: ProgramCompletion)

    fun loadCached(): Pair<ProgramCompletion, Long>? = cache.read<Cached>("completion", Cached::class.java)
        ?.let { it.data to it.fetchedAt }

    suspend fun refresh(): AhuResult<Pair<ProgramCompletion, Long>> = try {
        val generation = AccountGeneration.current
        PluginScope.requireUndergraduate()
        val id = cache.string("student_id") ?: resolveStudentId().also { cache.put("student_id", it) }
        val (_, html) = PluginScope.page("/student/for-std/program-completion-preview/info/$id")
        val data = ProgramCompletionHtmlParser.parse(html)
        AccountGeneration.requireCurrent(generation)
        val fetchedAt = System.currentTimeMillis()
        cache.save("completion", Cached(fetchedAt, data))
        AhuResult.Success(data to fetchedAt)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { AhuResult.Failure(failure.toPluginError()) }

    private suspend fun resolveStudentId(): String {
        val (url, html) = PluginScope.page("/student/for-std/program-completion-preview")
        url.substringAfterLast('/').takeIf { it.toLongOrNull() != null }?.let { return it }
        val page = Jsoup.parse(html)
        page.select(".student-panel-block button[onclick*=myFunction]").first()?.let { button ->
            button.attr("value").takeIf { it.toLongOrNull() != null }?.let { return it }
            Regex("\\d+").find(button.attr("onclick"))?.value?.let { return it }
        }
        val (gradeUrl, gradeHtml) = PluginScope.page("/student/for-std/grade/sheet")
        gradeUrl.substringAfterLast('/').takeIf { it.toLongOrNull() != null }?.let { return it }
        Jsoup.parse(gradeHtml).select(".student-panel-block button[onclick*=myFunction]").first()?.let { button ->
            button.attr("value").takeIf { it.toLongOrNull() != null }?.let { return it }
            Regex("\\d+").find(button.attr("onclick"))?.value?.let { return it }
        }
        error("无法解析教务学生档案，请在校园账号中重新登录")
    }
}
