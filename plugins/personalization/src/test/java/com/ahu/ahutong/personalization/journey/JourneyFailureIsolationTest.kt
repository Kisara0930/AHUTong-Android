package com.ahu.ahutong.personalization.journey

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JourneyFailureIsolationTest {
    @Test
    fun `all emitted journey labels are accepted by the trainer`() {
        assertTrue(JourneyTrainingLabelPolicy.accepts(JourneyTrainingLabelPolicy.ORGANIC_JOURNEY))
        assertTrue(JourneyTrainingLabelPolicy.accepts(JourneyTrainingLabelPolicy.INTERVENTION_FREE_TIMEOUT))
        assertTrue(JourneyTrainingLabelPolicy.accepts(JourneyTrainingLabelPolicy.INTERVENTION_FREE_MAX_STEPS))
        assertFalse(JourneyTrainingLabelPolicy.accepts("UNRECOGNIZED"))
    }

    @Test
    fun `background personalization scopes isolate uncaught failures`() {
        val root = repositoryRoot()
        val runtime = File(
            root,
            "plugins/personalization/src/main/java/com/ahu/ahutong/personalization/runtime/PredictionRuntime.kt"
        ).readText()
        val journey = File(
            root,
            "plugins/personalization/src/main/java/com/ahu/ahutong/personalization/journey/JourneyPredictionEngine.kt"
        ).readText()

        assertTrue(runtime.contains("CoroutineExceptionHandler"))
        assertTrue(journey.contains("CoroutineExceptionHandler"))
        assertTrue(journey.contains("dwellJobs[pending.journeyId] = scope.launch"))
        assertTrue(journey.contains("deadlineJobs[pending.journeyId] = scope.launch"))
    }

    private fun repositoryRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
    }
}
