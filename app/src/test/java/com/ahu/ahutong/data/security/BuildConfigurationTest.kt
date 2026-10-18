package com.ahu.ahutong.data.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 构建配置的安全护栏。
 *
 * `NetworkSecurityConfigTest` 用测试钉住了"明文只允许回环"这条不变量，说明这类护栏确实有效；
 * 但 `org.gradle.dependency.verification=off` 曾经没有任何护栏拦住它——它静默关掉了
 * `gradle/verification-metadata.xml` 里的全部 sha256 校验，只看文件变更列表根本察觉不到。
 * 这里按同一思路把两条构建期不变量钉住。
 */
class BuildConfigurationTest {

    @Test
    fun `dependency verification is never switched off`() {
        val properties = File(repositoryRoot(), "gradle.properties").readText()

        assertFalse(
            properties.contains("org.gradle.dependency.verification=off"),
            "不得在 gradle.properties 里关闭依赖校验：校验失败应更新 " +
                "gradle/verification-metadata.xml，而不是关掉全局开关。"
        )
    }

    @Test
    fun `the verification metadata is still present`() {
        val metadata = File(repositoryRoot(), "gradle/verification-metadata.xml")

        assertTrue(metadata.isFile, "gradle/verification-metadata.xml 缺失，供应链校验已失效")
    }

    @Test
    fun `no committed daemon jvm criteria pins the build to one jdk`() {
        val daemonJvm = File(repositoryRoot(), "gradle/gradle-daemon-jvm.properties")

        // 这是 updateDaemonJvm 的工具产物，会把 Gradle daemon 钉死在一个 JDK 上；
        // 仓库没有 foojay-resolver，toolchainUrl.* 无法自动置备，构建能否成功将取决于
        // 机器上碰巧装了什么 JDK。CI 由 setup-java 显式声明版本，不该被它绕过。
        assertFalse(
            daemonJvm.exists(),
            "gradle/gradle-daemon-jvm.properties 不应被提交：它把构建钉死在单个 JDK 上。"
        )
    }

    private fun repositoryRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { it.parentFile }
            .first { File(it, "gradle.properties").isFile }
    }
}
