package com.ahu.ahutong.data.notice

import java.net.URI
import java.time.LocalDate
import org.jsoup.Jsoup

/** Strict parser for the public research-school notice column. */
internal object PostgraduateNoticeParser {
    const val SOURCE_ID = "postgraduate"
    const val FIRST_PAGE = "https://graschool.ahu.edu.cn/9577/list.htm"
    private val listPath = Regex("/9577/list(?:m?\\d*|\\d+m)\\.htm")
    private val articlePath = Regex("/\\d{4}/\\d{4}/c9577a\\d+/pagem?\\.htm")
    private val articleKey = Regex("c9577a\\d+")

    fun isTrustedListUrl(raw: String?): Boolean = trustedUrl(raw, listPath)
    fun isTrustedArticleUrl(raw: String?): Boolean = trustedUrl(raw, articlePath)

    private fun trustedUrl(raw: String?, path: Regex): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "graschool.ahu.edu.cn" &&
            uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
            path.matches(uri.path)
    }.getOrDefault(false)

    fun parse(html: String, pageUrl: String): CampusNoticePage {
        if (!isTrustedListUrl(pageUrl)) throw CampusNoticeProtocolException("研究生院公告列表地址无效")
        val document = Jsoup.parse(html, pageUrl)
        val rows = document.select("ul.wp_article_list > li.list_item")
        if (rows.isEmpty()) throw CampusNoticeProtocolException("研究生院公告列表结构已变化")
        val notices = rows.map { row ->
            val link = row.selectFirst("span.Article_Title a[href]")
                ?: throw CampusNoticeProtocolException("研究生院公告缺少文章链接")
            val title = link.attr("title").ifBlank { link.text() }.trim()
                .takeIf(String::isNotBlank)
                ?: throw CampusNoticeProtocolException("研究生院公告缺少标题")
            val date = row.selectFirst("span.Article_PublishDate")?.text()?.trim()
                ?: throw CampusNoticeProtocolException("研究生院公告缺少日期")
            runCatching { LocalDate.parse(date) }
                .getOrElse { throw CampusNoticeProtocolException("研究生院公告日期格式已变化") }
            val url = normalizedUrl(pageUrl, link.attr("href"), articlePath)
            val id = articleKey.find(URI(url).path)?.value
                ?: throw CampusNoticeProtocolException("研究生院公告文章 ID 无效")
            CampusNotice(SOURCE_ID, id, title, date, url, discoveredAtMillis = 0, read = false)
        }
        val nextHref = document.selectFirst("a.next[href]")?.attr("href")
            ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) || it == "#" }
        val next = nextHref?.let { normalizedUrl(pageUrl, it, listPath) }
        return CampusNoticePage(notices.distinctBy(CampusNotice::articleId), next)
    }

    private fun normalizedUrl(base: String, href: String, path: Regex): String {
        val uri = runCatching { URI(base).resolve(href.trim()).normalize() }.getOrNull()
            ?: throw CampusNoticeProtocolException("研究生院公告链接无效")
        if (uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) {
            throw CampusNoticeProtocolException("研究生院公告链接包含不受信任参数")
        }
        val normalized = URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        if (!trustedUrl(normalized, path)) throw CampusNoticeProtocolException("研究生院公告链接超出受信任栏目")
        return normalized
    }
}
