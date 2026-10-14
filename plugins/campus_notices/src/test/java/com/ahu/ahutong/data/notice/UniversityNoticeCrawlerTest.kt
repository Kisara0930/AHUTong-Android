package com.ahu.ahutong.data.notice

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversityNoticeCrawlerTest {
    private fun article(number: Int, title: String = "公告$number"): String =
        """<li class="news"><span class="news_title"><a href="/2026/0925/c15046a$number/page.htm" title="$title">$title</a></span><span class="news_meta">2026-09-25</span></li>"""

    private fun page(vararg articles: String, next: String? = null): String =
        """<html><ul class="news_list list2">${articles.joinToString("")}</ul><li class="page_nav"><a class="next" href="${next ?: "javascript:void(0);"}">下一页</a></li></html>"""

    @Test fun `first sync seeds one page without traversing years of history`() = runTest {
        var requests = 0
        val crawler = UniversityNoticeCrawler { _ -> requests++; page(article(14), next = "/15046/list2.htm") }

        val notices = crawler.fetchSince(emptySet(), firstSync = true)

        assertEquals(listOf("c15046a14"), notices.map(CampusNotice::articleId))
        assertEquals(1, requests)
    }

    @Test fun `new articles beyond page one are found until an exact seen ID`() = runTest {
        val requested = mutableListOf<String>()
        val crawler = UniversityNoticeCrawler { url ->
            requested += url
            if (url.endsWith("list.htm")) page(article(16), next = "/15046/list2.htm")
            else page(article(15), article(14))
        }

        val notices = crawler.fetchSince(setOf("c15046a14"), firstSync = false)

        assertEquals(listOf("c15046a16", "c15046a15"), notices.map(CampusNotice::articleId))
        assertEquals(2, requested.size)
    }

    @Test fun `only exact article IDs deduplicate and canonical URLs are retained`() = runTest {
        val crawler = UniversityNoticeCrawler { _ ->
            page(article(16), article(16, "标题已编辑"), article(15), article(14))
        }

        val notices = crawler.fetchSince(setOf("c15046a14"), firstSync = false)

        assertEquals(2, notices.size)
        assertEquals("https://www.ahu.edu.cn/2026/0925/c15046a16/page.htm", notices.first().originalUrl)
    }

    @Test fun `no seen anchor or malformed URL fails instead of notifying historical posts`() = runTest {
        val crawler = UniversityNoticeCrawler { _ -> page(article(16)) }
        try {
            crawler.fetchSince(setOf("c15046a14"), firstSync = false)
            throw AssertionError("Expected history gap")
        } catch (error: CampusNoticeProtocolException) {
            assertTrue(error.message.orEmpty().contains("已见"))
        }
        val bad = page("""<li class="news"><span class="news_title"><a href="https://example.com/post" title="越界">越界</a></span><span class="news_meta">2026-09-25</span></li>""")
        assertThrows(CampusNoticeProtocolException::class.java) {
            UniversityNoticeParser.parse(bad, UniversityNoticeParser.FIRST_PAGE)
        }
    }

    @Test fun `HTTP 412 and transport failures are not treated as empty lists`() = runTest {
        for (error in listOf(CampusNoticeAccessBlockedException(), IOException("offline"))) {
            val crawler = UniversityNoticeCrawler { _ -> throw error }
            try {
                crawler.fetchSince(emptySet(), firstSync = true)
                throw AssertionError("Expected source failure")
            } catch (actual: IOException) {
                assertEquals(error::class.java, actual::class.java)
            }
        }
    }
}
