package com.ahu.ahutong.data.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSecurityConfigTest {
    @Test
    fun `cleartext is limited to the loopback bridge and the documented campus card host`() {
        val xml = File(repositoryRoot(), "app/src/main/res/xml/network_security_config.xml")
            .readText()

        assertTrue(xml.contains("<base-config cleartextTrafficPermitted=\"false\""))

        val cleartextDomains = CLEARTEXT_DOMAIN_CONFIG.findAll(xml).map { block ->
            DOMAIN.findAll(block.groupValues[1]).map { it.groupValues[1].trim() }.toSet()
        }.toList()

        // 明文例外是白名单：每新增一处都必须重新评估安全前提（该校一卡通端点只有明文 HTTP，
        // 是已知且登记过的例外）。数量变化会在这里被拦下，而不是悄悄放行。
        assertEquals(
            2,
            cleartextDomains.size,
            "明文例外必须恰好两处（回环桥 + 已登记的自定义一卡通端点）：$cleartextDomains"
        )
        assertTrue(
            cleartextDomains.contains(setOf("127.0.0.1", "localhost")),
            "回环桥的明文例外丢失：$cleartextDomains"
        )
        assertTrue(
            cleartextDomains.contains(setOf("ykt.hfue.edu.cn")),
            "自定义一卡通端点的明文例外必须是唯一且精确的一条：$cleartextDomains"
        )
    }

    @Test
    fun `query credentials never use the HTTP logging client`() {
        val source = File(
            repositoryRoot(),
            "app/src/main/java/com/ahu/ahutong/data/adapter/RepositoryNetworkRechargeSource.kt"
        ).readText()

        assertTrue(source.contains("YcardApi.loginRedirectClient.newCall(request)"))
        assertFalse(source.contains("YcardApi.okHttpClient.newBuilder()"))
    }

    @Test
    fun `the internal APK fallback is exposed only from its update directory`() {
        val paths = File(repositoryRoot(), "app/src/main/res/xml/file_paths.xml").readText()
        val directory = File(
            repositoryRoot(),
            "app/src/main/java/com/ahu/ahutong/data/adapter/AppApkDirectory.kt"
        ).readText()

        assertTrue(paths.contains("<files-path name=\"update_files\" path=\"updates/\""))
        assertTrue(directory.contains("File(context.filesDir, \"updates\")"))
        assertFalse(paths.contains("<files-path name=\"internal_files\" path=\".\""))
    }

    private fun repositoryRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { it.parentFile }
            .first { File(it, "app/src/main/res").isDirectory }
    }

    private companion object {
        val CLEARTEXT_DOMAIN_CONFIG = Regex(
            "<domain-config cleartextTrafficPermitted=\"true\"[^>]*>(.*?)</domain-config>",
            RegexOption.DOT_MATCHES_ALL
        )
        val DOMAIN = Regex("<domain[^>]*>([^<]+)</domain>")
    }
}
