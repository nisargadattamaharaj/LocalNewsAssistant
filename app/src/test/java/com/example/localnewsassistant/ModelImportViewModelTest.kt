package com.example.localnewsassistant

import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.LlmInferenceService
import com.example.localnewsassistant.model.NewsSummaryPostProcessor
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportViewModelTest {

    private val sampleDataset = SyntheticNewsDataset(
        datasetId = "synthetic-news-v1",
        synthetic = true,
        messages = listOf(
            SyntheticNewsMessage(
                id = "N001",
                source = "Тестовый источник Альфа",
                publishedAt = "2026-10-12T09:00:00+03:00",
                text = "Вымышленный центр «Север» откроет лабораторию в Ладограде 15 октября 2026 года на 10 рабочих мест."
            ),
            SyntheticNewsMessage(
                id = "N002",
                source = "Тестовый источник Бета",
                publishedAt = "2026-10-12T10:00:00+03:00",
                text = "Вымышленная компания «Маяк» затормозила запуск сервиса «Обзор»."
            )
        )
    )

    class ControllableSummaryLlmService : LlmInferenceService {
        var generateCallCount = 0
        var throwUnsatisfiedLink = false
        var lastReceivedPrompt: String? = null
        val initStarted = CompletableDeferred<Unit>()
        val initBlocker = CompletableDeferred<Unit>()

        override suspend fun generateResponse(
            modelFileAbsolutePath: String,
            prompt: String,
            onInitFinished: () -> Unit
        ): LlmExecutionResult {
            generateCallCount++
            lastReceivedPrompt = prompt

            if (throwUnsatisfiedLink) {
                throw UnsatisfiedLinkError("Не удалось загрузить нативную библиотеку liblitertlm.so")
            }

            initStarted.complete(Unit)
            initBlocker.await()
            onInitFinished()

            return LlmExecutionResult(
                responseText = "- Вымышленный центр «Север» откроет лабораторию в Ладограде. [N001]\n- Компания «Маяк» объявила о сервисе. [N002]",
                initTimeMs = 200L,
                inferenceTimeMs = 500L,
                backendName = "CPU"
            )
        }
    }

    @Test
    fun testSummaryPipelineWithParserPromptAndProcessor() {
        val dataset = sampleDataset
        val prompt = NewsSummaryPromptBuilder.buildPrompt(dataset)

        assertTrue(prompt.contains("<DATA_START>"))
        assertTrue(prompt.contains("[N001]"))
        assertTrue(prompt.contains("[N002]"))

        val mockLlmResponse = "- Центр «Север» откроет лабораторию. [N001]\n- Компания «Маяк» затормозила проект. [N002]"
        val summaryResult = NewsSummaryPostProcessor.processSummary(mockLlmResponse, dataset)

        assertTrue(summaryResult.areCitationsValid)
        assertEquals(2, summaryResult.bulletPoints.size)
        assertEquals(listOf("N001"), summaryResult.bulletPoints[0].citedIds)
        assertEquals(listOf("N002"), summaryResult.bulletPoints[1].citedIds)
    }

    @Test
    fun testEmptyLlmResponseHandlingInSummary() {
        val dataset = sampleDataset
        val summaryResult = NewsSummaryPostProcessor.processSummary("", dataset)

        assertFalse(summaryResult.areCitationsValid)
        assertTrue(summaryResult.warningMessages.any { it.contains("пустой ответ") })
    }

    @Test
    fun testUnsatisfiedLinkErrorHandlingInService() = runBlocking {
        val service = ControllableSummaryLlmService().apply { throwUnsatisfiedLink = true }
        var caughtException: Throwable? = null

        try {
            service.generateResponse("/path/to/model", "Test prompt")
        } catch (e: Throwable) {
            caughtException = e
        }

        assertTrue(caughtException is UnsatisfiedLinkError)
        assertTrue(caughtException!!.message!!.contains("liblitertlm.so"))
    }
}
