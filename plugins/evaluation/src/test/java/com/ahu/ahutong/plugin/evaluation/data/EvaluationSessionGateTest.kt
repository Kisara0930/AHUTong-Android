package com.ahu.ahutong.plugin.evaluation.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class EvaluationSessionGateTest {

    @Test
    fun `session clear waits for the in-flight account request`() = runBlocking {
        val gate = EvaluationSessionGate()
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        var cleared = false

        val request = async {
            gate.withSession {
                requestStarted.complete(Unit)
                finishRequest.await()
            }
        }
        requestStarted.await()
        val clear = async { gate.withSession { cleared = true } }
        yield()

        assertFalse(clear.isCompleted)
        finishRequest.complete(Unit)
        request.await()
        clear.await()
        assertTrue(cleared)
    }
}
