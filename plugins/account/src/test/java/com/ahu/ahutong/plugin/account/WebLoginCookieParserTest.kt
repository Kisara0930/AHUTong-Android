package com.ahu.ahutong.plugin.account

import kotlin.test.Test
import kotlin.test.assertEquals

class WebLoginCookieParserTest {

    @Test
    fun parsesCookieValuesContainingEqualsSigns() {
        assertEquals(
            listOf(
                "SESSION" to "abc==",
                "challenge" to "value"
            ),
            parseWebLoginCookiePairs("SESSION=abc==; challenge=value")
        )
    }

    @Test
    fun ignoresBlankAndMalformedCookieParts() {
        assertEquals(
            listOf("valid" to "1"),
            parseWebLoginCookiePairs(" ; invalid; =missingName; valid=1")
        )
    }
}
