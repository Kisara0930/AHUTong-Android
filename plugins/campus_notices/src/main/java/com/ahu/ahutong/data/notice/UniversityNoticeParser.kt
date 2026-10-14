package com.ahu.ahutong.data.notice

import java.net.URI
import java.time.LocalDate
import org.jsoup.Jsoup

/** Parser for the verified www.ahu.edu.cn /15046 notice list, not a generic site scraper. */
internal object UniversityNoticeParser {
    const val SOURCE_ID = "university"
    const val FIRST_PAGE = "https://www.ahu.edu.cn/15046/list.htm"
    private val articlePath = Regex("/\\d{4}/\\d{4}/c\\d+a\\d+/page\\.htm")
    private val listPath = Regex("/15046/list\\d*\\.htm")
    private val articleKey = Regex("c\\d+a\\d+")

    fun isTrustedArticleUrl(raw: String?): Boolean = runCatching {
        val url = URI(raw)
        url.scheme == "https" && url.host == "www.ahu.edu.cn" &&
            url.userInfo == null && articlePath.matches(url.path)
    }.getOrDefault(false)

    fun parse(html: String, pageUrl: String): CampusNoticePage {
        val document = Jsoup.parse(html, pageUrl)
        val rows = document.select("ul.news_list.list2 > li.news")
        if (rows.isEmpty()) throw CampusNoticeProtocolException("校级通知列表结构已变化")

        val notices = rows.map { row ->
            val link = row.selectFirst("span.news_title a[href]")
                ?: throw CampusNoticeProtocolException("校级通知缺少文章链接")
            val title = link.attr("title").ifBlank { link.text() }.trim()
                .takeIf { it.isNotBlank() }
                ?: throw CampusNoticeProtocolException("校级通知缺少标题")
            val date = row.selectFirst("span.news_meta")?.text()?.trim()
                ?: throw CampusNoticeProtocolException("校级通知缺少日期")
            try {
                LocalDate.parse(date)
            } catch (_: Exception) {
                throw CampusNoticeProtocolException("校级通知日期格式已变化")
            }
            val url = normalizedUrl(pageUrl, link.attr("href"), articlePath)
            val id = articleKey.find(URI(url).path)?.value
                ?: throw CampusNoticeProtocolException("校级通知文章 ID 无效")
            CampusNotice(SOURCE_ID, id, title, date, url, discoveredAtMillis = 0, read = false)
        }

        val nextHref = document.selectFirst("li.page_nav a.next[href]")?.attr("href")
            ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) || it == "#" }
        val next = nextHref?.let { normalizedUrl(pageUrl, it, listPath) }
        return CampusNoticePage(notices.distinctBy(CampusNotice::articleId), next)
    }

    private fun normalizedUrl(base: String, href: String, allowedPath: Regex): String {
        val url = runCatching { URI(base).resolve(href.trim()).normalize() }.getOrNull()
            ?: throw CampusNoticeProtocolException("校级通知链接无效")
        if (url.scheme != "https" || url.host != "www.ahu.edu.cn" ||
            url.userInfo != null || !allowedPath.matches(url.path)
        ) throw CampusNoticeProtocolException("校级通知链接超出受信任栏目")
        return URI(url.scheme, null, url.host, -1, url.path, null, null).toString()
    }
}
