package com.example.localnewsassistant

import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.LlmInferenceService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmInferenceTest {

    class FakeLlmService : LlmInferenceService {
        var shouldFailInit = false
        var returnEmpty = false
        var generateCallCount = 0

        override suspend fun generateResponse(
            modelFileAbsolutePath: String,
            prompt: String,
            onInitFinished: () -> Unit
        ): LlmExecutionResult {
            generateCallCount++
            if (shouldFailInit) {
                throw IllegalStateException("Ошибка при инициализации модели")
            }
            onInitFinished()
            if (returnEmpty) {
                return LlmExecutionResult("", 100L, 200L, "CPU")
            }
            return LlmExecutionResult(
                responseText = "Локальная модель работает без интернета.",
                initTimeMs = 500L,
                inferenceTimeMs = 300L,
                backendName = "CPU"
            )
        }
    }

    @Test
    fun testFakeLlmSuccessResponse() {
        val fakeService = FakeLlmService()

        runBlocking {
            val result = fakeService.generateResponse("/path/to/model", "test prompt")
            assertEquals("Локальная модель работает без интернета.", result.responseText)
            assertEquals("CPU", result.backendName)
            assertTrue(result.initTimeMs >= 0)
            assertTrue(result.inferenceTimeMs >= 0)
        }
    }

    @Test
    fun testFakeLlmInitError() {
        val fakeService = FakeLlmService().apply { shouldFailInit = true }

        runBlocking {
            var caught = false
            try {
                fakeService.generateResponse("/path/to/model", "test prompt")
            } catch (_: Exception) {
                caught = true
            }
            assertTrue(caught)
        }
    }

    @Test
    fun testFakeLlmEmptyResponse() {
        val fakeService = FakeLlmService().apply { returnEmpty = true }

        runBlocking {
            val result = fakeService.generateResponse("/path/to/model", "test prompt")
            assertTrue(result.responseText.isEmpty())
        }
    }
}
