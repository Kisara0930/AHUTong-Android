package com.ahu.ahutong.plugin.account

internal fun parseWebLoginCookiePairs(cookieHeader: String?): List<Pair<String, String>> {
    if (cookieHeader.isNullOrBlank()) return emptyList()

    return cookieHeader
        .split(';')
        .mapNotNull { rawCookie ->
            val cookie = rawCookie.trim()
            val separatorIndex = cookie.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null

            val name = cookie.substring(0, separatorIndex).trim()
            val value = cookie.substring(separatorIndex + 1).trim()
            name.takeIf { it.isNotEmpty() }?.let { it to value }
        }
}
