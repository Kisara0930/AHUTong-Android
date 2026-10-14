package com.ahu.ahutong.data.notice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostgraduateNoticeCrawlerTest {
    private fun article(id: Int, date: String, title: String = "公告$id"): String =
        """<li class="list_item i$id"><span class="Article_Title"><a href="/2026/0925/c9577a$id/page.htm" title="$title">$title</a></span><span class="Article_PublishDate">$date</span></li>"""

    private fun page(vararg articles: String, next: String? = null): String =
        """<html><ul class="wp_article_list">${articles.joinToString("")}</ul><a class="next" href="${next ?: "javascript:void(0);"}">下一页</a></html>"""

    @Test fun `parser extracts official article and next page`() {
        val parsed = PostgraduateNoticeParser.parse(
            page(article(400137, "2026-09-10", "奖助学金发放进度"), next = "/9577/list2.htm"),
            PostgraduateNoticeParser.FIRST_PAGE
        )
        assertEquals("c9577a400137", parsed.notices.single().articleId)
        assertEquals("2026-09-10", parsed.notices.single().publishedOn)
        assertEquals("https://graschool.ahu.edu.cn/9577/list2.htm", parsed.nextPageUrl)
        assertTrue(CampusNoticeUrlPolicy.isTrustedArticle("postgraduate", parsed.notices.single().originalUrl))
        assertFalse(CampusNoticeUrlPolicy.isTrustedArticle("postgraduate", "https://evil.example/2026/0925/c9577a400137/page.htm"))
    }

    @Test fun `mobile article and pagination URLs stay inside the same official column`() {
        val html = """<html><ul class="wp_article_list"><li class="list_item"><span class="Article_Title"><a href="/2026/0910/c9577a400137/pagem.htm">移动端公告</a></span><span class="Article_PublishDate">2026-09-10</span></li></ul><a class="next" href="/9577/listm2.htm">下一页</a></html>"""
        val parsed = PostgraduateNoticeParser.parse(html, PostgraduateNoticeParser.FIRST_PAGE)
        assertEquals("https://graschool.ahu.edu.cn/2026/0910/c9577a400137/pagem.htm", parsed.notices.single().originalUrl)
        assertEquals("https://graschool.ahu.edu.cn/9577/listm2.htm", parsed.nextPageUrl)
    }

    @Test fun `first sync seeds one page`() = runTest {
        var requests = 0
        val crawler = PostgraduateNoticeCrawler { _ ->
            requests++
            page(article(1, "2026-09-10"), next = "/9577/list2.htm")
        }
        assertEquals(listOf("c9577a1"), crawler.fetchSince(emptySet(), firstSync = true).map(CampusNotice::articleId))
        assertEquals(1, requests)
    }

    @Test fun `known older pinned entry does not hide newer article below it`() = runTest {
        val crawler = PostgraduateNoticeCrawler { _ ->
            page(
                article(1, "2026-09-10", "置顶进度"),
                article(3, "2026-09-18"),
                article(2, "2026-09-17")
            )
        }
        val fresh = crawler.fetchSince(setOf("c9577a1", "c9577a2"), firstSync = false)
        assertEquals(listOf("c9577a3"), fresh.map(CampusNotice::articleId))
    }

    @Test fun `first-row rolling notice remains non-anchor after its date changes`() = runTest {
        val crawler = PostgraduateNoticeCrawler { _ ->
            page(article(1, "2026-09-25", "实时更新中"), article(3, "2026-09-24"), article(2, "2026-09-17"))
        }
        val fresh = crawler.fetchSince(setOf("c9577a1", "c9577a2"), firstSync = false)
        assertEquals(listOf("c9577a3"), fresh.map(CampusNotice::articleId))
    }

    @Test fun `new articles spanning pages stop at non-pinned known anchor`() = runTest {
        var requests = 0
        val crawler = PostgraduateNoticeCrawler { url ->
            requests++
            if (url.endsWith("list.htm")) page(
                article(1, "2026-09-10"), article(4, "2026-09-19"), article(3, "2026-09-18"),
                next = "/9577/list2.htm"
            ) else page(article(2, "2026-09-17"))
        }
        val fresh = crawler.fetchSince(setOf("c9577a1", "c9577a2"), firstSync = false)
        assertEquals(listOf("c9577a4", "c9577a3"), fresh.map(CampusNotice::articleId))
        assertEquals(2, requests)
    }

    @Test fun `missing seen anchor fails instead of treating history as new`() = runTest {
        val crawler = PostgraduateNoticeCrawler { _ -> page(article(4, "2026-09-19")) }
        try {
            crawler.fetchSince(setOf("c9577a2"), firstSync = false)
            throw AssertionError("expected missing anchor")
        } catch (error: CampusNoticeProtocolException) {
            assertTrue(error.message.orEmpty().contains("已见"))
        }
        assertThrows(CampusNoticeProtocolException::class.java) {
            PostgraduateNoticeParser.parse(
                page("""<li class="list_item"><span class="Article_Title"><a href="https://evil.example/post">bad</a></span><span class="Article_PublishDate">2026-09-25</span></li>"""),
                PostgraduateNoticeParser.FIRST_PAGE
            )
        }
    }
}
