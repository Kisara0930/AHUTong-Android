package com.ahu.ahutong.plugin.account

private const val JWXT_SSO_HOST = "jw.ahu.edu.cn"
private const val JWXT_SSO_PATH = "/student/sso/login"

internal fun isJwxtSsoUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false

    return runCatching {
        val uri = java.net.URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(JWXT_SSO_HOST, ignoreCase = true) &&
            uri.path?.trimEnd('/') == JWXT_SSO_PATH
    }.getOrDefault(false)
}
