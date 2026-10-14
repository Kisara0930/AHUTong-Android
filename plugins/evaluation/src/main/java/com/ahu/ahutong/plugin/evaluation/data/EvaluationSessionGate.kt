package com.ahu.ahutong.plugin.evaluation.data

import kotlinx.coroutines.sync.Mutex

/** 评教请求与账号清理共用的窄串行门，避免全局 Authorization 在请求途中换账号。 */
internal class EvaluationSessionGate {
    private val mutex = Mutex()

    suspend fun <T> withSession(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
