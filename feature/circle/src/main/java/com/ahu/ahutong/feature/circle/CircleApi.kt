package com.ahu.ahutong.feature.circle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 校园圈子只读客户端：直连 BBS 官方公开接口，全程零鉴权。
 *
 * 请求头沿用 AHU Plus 观察到的该服务对小程序客户端的期望形态
 * （Tenant 标识学校租户；该服务未提供其他第三方接入方式）。
 *
 * HTTP 入口是宿主提供的裸客户端（[OkHttpClient] 经契约传入），
 * 本类只给请求加 BBS 所需的公开 Header，不碰主 App 的任何会话。
 */
class CircleApi(baseClient: OkHttpClient) {

    private val client = baseClient

    /** 帖子列表（分页，页码从 1 开始）。 */
    suspend fun fetchTopics(page: Int): CircleTopicPage = withContext(Dispatchers.IO) {
        require(page >= 1) { "页码必须从 1 开始" }
        val json = getJson("$BASE/api/client/topics?page=$page&pageSize=$PAGE_SIZE")
        val topics = extractArray(json).let { arr ->
            (0 until arr.length()).mapNotNull { parseTopic(arr.optJSONObject(it)) }
        }
        CircleTopicPage(topics = topics, page = page, hasMore = topics.size >= PAGE_SIZE)
    }

    /** 帖子详情（只读端点）。 */
    suspend fun fetchTopicDetail(id: String): CircleTopic = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/client/topics/read_only")
            .addPathSegment(id).build()
        val json = getJson(url.toString())
        val obj = extractObject(json)
        parseTopic(obj) ?: throw CircleApiException("帖子详情解析失败")
    }

    /** 评论列表。 */
    suspend fun fetchComments(topicId: String): List<CircleComment> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/client/comments")
            .addQueryParameter("topic_id", topicId).addQueryParameter("page", "1")
            .addQueryParameter("pageSize", "100").build()
        val json = getJson(url.toString())
        val arr = extractArray(json)
        (0 until arr.length()).mapNotNull { parseComment(arr.optJSONObject(it)) }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get()
            .header("Tenant", TENANT)
            .header("xweb_xhr", "1")
            .header("Referer", MINI_PROGRAM_REFERER)
            .header("User-Agent", DESKTOP_UA)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw CircleApiException("HTTP ${response.code}")
            return runCatching { JSONObject(body) }.getOrElse {
                // 有的端点直接返回数组
                throw CircleApiException("响应不是 JSON")
            }
        }
    }

    /** 防御式取出列表数组：兼容 {data:{list:[]}} / {data:[]} / {list:[]} 等常见包裹。 */
    private fun extractArray(root: JSONObject): JSONArray {
        root.optJSONObject("data")?.let { data ->
            data.optJSONArray("list")?.let { return it }
            data.optJSONArray("topics")?.let { return it }
            data.optJSONArray("comments")?.let { return it }
            data.optJSONArray("items")?.let { return it }
        }
        root.optJSONArray("data")?.let { return it }
        root.optJSONArray("list")?.let { return it }
        root.optJSONArray("topics")?.let { return it }
        root.optJSONArray("comments")?.let { return it }
        return JSONArray()
    }

    private fun extractObject(root: JSONObject): JSONObject =
        root.optJSONObject("data") ?: root

    private fun parseTopic(obj: JSONObject?): CircleTopic? {
        obj ?: return null
        val id = obj.optFirstString("id", "topic_id", "topicId") ?: return null
        val user = obj.optJSONObject("userInfo") ?: obj.optJSONObject("user")
        return CircleTopic(
            id = id,
            title = obj.optFirstString("title", "name") ?: "",
            content = obj.optFirstString("content", "text") ?: "",
            nodeName = obj.optJSONObject("node")?.optFirstString("name")
                ?: obj.optFirstString("node_name", "nodeName") ?: "",
            authorName = user?.optFirstString("nickname", "name") ?: "匿名",
            commentCount = obj.optFirstInt("commentCount", "comment_count", "comments"),
            likeCount = obj.optFirstInt("likeCount", "like_count", "likes"),
            viewCount = obj.optFirstInt("viewCount", "view_count", "views"),
            isAnon = obj.optFirstInt("is_anon", "isAnon") == 1,
            isTop = obj.optFirstInt("is_top", "isTop") == 1,
            createTime = obj.optFirstString("createTime", "create_time", "created_at") ?: "",
            imageUrls = obj.optJSONArray("imgs")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
        )
    }

    private fun parseComment(obj: JSONObject?): CircleComment? {
        obj ?: return null
        val id = obj.optFirstString("id", "comment_id") ?: return null
        val user = obj.optJSONObject("userInfo") ?: obj.optJSONObject("user")
        return CircleComment(
            id = id,
            authorName = user?.optFirstString("nickname", "name") ?: "匿名",
            content = obj.optFirstString("content", "text") ?: "",
            createTime = obj.optFirstString("createTime", "create_time", "created_at") ?: ""
        )
    }

    private fun JSONObject.optFirstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() && it != "null" } else null
        }

    private fun JSONObject.optFirstInt(vararg keys: String): Int =
        keys.firstNotNullOfOrNull { k ->
            if (has(k) && !isNull(k)) optString(k).toIntOrNull() else null
        } ?: 0

    companion object {
        private const val BASE = "https://api.zxs-bbs.cn"
        private const val TENANT = "7" // 安徽大学租户号
        private const val MINI_PROGRAM_REFERER =
            "https://servicewechat.com/wxc56be16e96fc1df1/66/page-frame.html"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PAGE_SIZE = 20
    }
}

class CircleApiException(message: String) : Exception(message)
