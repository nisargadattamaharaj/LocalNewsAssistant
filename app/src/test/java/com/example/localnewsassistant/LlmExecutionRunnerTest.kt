package com.example.localnewsassistant

import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.LlmExecutionRunner
import com.example.localnewsassistant.model.LlmInferenceService
import com.example.localnewsassistant.ui.LlmExecutionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmExecutionRunnerTest {

    class ControllableLlmService : LlmInferenceService {
        var receivedPrompt: String? = null
        var failDuringInit = false
        var failDuringGeneration = false
        var returnEmpty = false
        var generateCallCount = 0

        val initStarted = CompletableDeferred<Unit>()
        val initBlocker = CompletableDeferred<Unit>()

        override suspend fun generateResponse(
            modelFileAbsolutePath: String,
            prompt: String,
            onInitFinished: () -> Unit
        ): LlmExecutionResult {
            receivedPrompt = prompt
            generateCallCount++
            if (failDuringInit) {
                throw IllegalStateException("Ошибка при инициализации модели")
            }

            initStarted.complete(Unit)
            initBlocker.await()

            onInitFinished()

            if (failDuringGeneration) {
                throw IllegalStateException("Ошибка во время генерации ответа")
            }

            if (returnEmpty) {
                return LlmExecutionResult("", 100L, 200L, "CPU")
            }

            return LlmExecutionResult(
                responseText = "Локальный ответ модели.",
                initTimeMs = 500L,
                inferenceTimeMs = 300L,
                backendName = "CPU"
            )
        }
    }

    @Test
    fun testStateTransitionsIdleInitializingGeneratingSuccess() = runBlocking {
        val service = ControllableLlmService()
        val runner = LlmExecutionRunner(service)

        assertEquals(LlmExecutionState.Idle, runner.llmState.value)

        val job = launch {
            runner.runLlm("/path/to/model")
        }

        service.initStarted.await()
        assertEquals(LlmExecutionState.Initializing, runner.llmState.value)

        service.initBlocker.complete(Unit)
        job.join()

        assertTrue(runner.llmState.value is LlmExecutionState.Success)
        val success = runner.llmState.value as LlmExecutionState.Success
        assertEquals("Локальный ответ модели.", success.result.responseText)
        assertEquals("CPU", success.result.backendName)
    }

    @Test
    fun testErrorBeforeInitFinishes() = runBlocking {
        val service = ControllableLlmService().apply { failDuringInit = true }
        val runner = LlmExecutionRunner(service)

        runner.runLlm("/path/to/model")

        assertTrue(runner.llmState.value is LlmExecutionState.Error)
        val error = runner.llmState.value as LlmExecutionState.Error
        assertTrue(error.message.contains("Ошибка при инициализации"))
    }

    @Test
    fun testErrorAfterTransitionToGenerating() = runBlocking {
        val service = ControllableLlmService().apply { failDuringGeneration = true }
        val runner = LlmExecutionRunner(service)

        val job = launch {
            runner.runLlm("/path/to/model")
        }

        service.initStarted.await()
        service.initBlocker.complete(Unit)
        job.join()

        assertTrue(runner.llmState.value is LlmExecutionState.Error)
        val error = runner.llmState.value as LlmExecutionState.Error
        assertTrue(error.message.contains("Ошибка во время генерации"))
    }

    @Test
    fun testEmptyResponseTurnsIntoError() = runBlocking {
        val service = ControllableLlmService().apply { returnEmpty = true }
        val runner = LlmExecutionRunner(service)

        val job = launch {
            runner.runLlm("/path/to/model")
        }

        service.initStarted.await()
        service.initBlocker.complete(Unit)
        job.join()

        assertTrue(runner.llmState.value is LlmExecutionState.Error)
        val error = runner.llmState.value as LlmExecutionState.Error
        assertEquals("Модель вернула пустой ответ.", error.message)
    }

    @Test
    fun testSecondCallDuringExecutionIsIgnored() = runBlocking {
        val service = ControllableLlmService()
        val runner = LlmExecutionRunner(service)

        val job1 = launch {
            runner.runLlm("/path/to/model")
        }

        service.initStarted.await()

        // Attempt second concurrent execution while first is running
        runner.runLlm("/path/to/model")

        assertEquals(1, service.generateCallCount)

        service.initBlocker.complete(Unit)
        job1.join()

        assertEquals(1, service.generateCallCount)
        assertTrue(runner.llmState.value is LlmExecutionState.Success)
    }

    @Test
    fun testLockingReleasedAfterCompletionAllowingSecondRun() = runBlocking {
        val service = ControllableLlmService()
        val runner = LlmExecutionRunner(service)

        val job1 = launch { runner.runLlm("/path/to/model") }
        service.initStarted.await()
        service.initBlocker.complete(Unit)
        job1.join()

        assertEquals(1, service.generateCallCount)

        // Reset blocker for second run
        val service2 = ControllableLlmService().apply { initBlocker.complete(Unit) }
        val runner2 = LlmExecutionRunner(service2)

        runner2.runLlm("/path/to/model")
        assertEquals(1, service2.generateCallCount)
        assertTrue(runner2.llmState.value is LlmExecutionState.Success)
    }

    @Test
    fun testPromptIsTransmittedUnchangedToService() = runBlocking {
        val service = ControllableLlmService()
        val runner = LlmExecutionRunner(service)

        val job = launch { runner.runLlm("/path/to/model") }
        service.initStarted.await()
        service.initBlocker.complete(Unit)
        job.join()

        assertEquals(
            "Ответь по-русски одним коротким предложением: что такое локальная языковая модель?",
            service.receivedPrompt
        )
    }

    @Test
    fun testResetStateResetsToIdle() = runBlocking {
        val service = ControllableLlmService()
        val runner = LlmExecutionRunner(service)

        val job = launch { runner.runLlm("/path/to/model") }
        service.initStarted.await()
        service.initBlocker.complete(Unit)
        job.join()

        assertTrue(runner.llmState.value is LlmExecutionState.Success)

        runner.resetState()
        assertEquals(LlmExecutionState.Idle, runner.llmState.value)
    }
}
