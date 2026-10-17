package com.example.localnewsassistant

import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.LlmInferenceService
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.NewsSummaryOrchestrator
import com.example.localnewsassistant.model.NewsSummaryPostProcessor
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.OperationCoordinator
import com.example.localnewsassistant.model.OperationType
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import com.example.localnewsassistant.ui.ModelUiState
import com.example.localnewsassistant.ui.SummaryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

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

    private val validMetadata = ModelMetadata("model.bin", 1000L, "model_active.bin")

    class ControllableSummaryLlmService : LlmInferenceService {
        var generateCallCount = 0
        var throwUnsatisfiedLink = false
        var throwIllegalState = false
        var customResponseText: String? = null
        var lastReceivedPrompt: String? = null
        val generateStarted = CompletableDeferred<Unit>()
        val generateBlocker = CompletableDeferred<Unit>()

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

            if (throwIllegalState) {
                throw IllegalStateException("LLM execution error")
            }

            generateStarted.complete(Unit)
            if (!generateBlocker.isCompleted) {
                generateBlocker.await()
            }
            onInitFinished()

            val text = customResponseText ?: "- Вымышленный центр «Север» откроет лабораторию в Ладограде. [N001]\n- Компания «Маяк» объявила о сервисе. [N002]"

            return LlmExecutionResult(
                responseText = text,
                initTimeMs = 200L,
                inferenceTimeMs = 500L,
                backendName = "CPU"
            )
        }
    }

    class TestNCancellationLlmService : LlmInferenceService {
        var generateCallCount = 0
        val generateStarted = CompletableDeferred<Unit>()
        val generateBlocker = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()

        override suspend fun generateResponse(
            modelFileAbsolutePath: String,
            prompt: String,
            onInitFinished: () -> Unit
        ): LlmExecutionResult {
            generateCallCount++
            generateStarted.complete(Unit)
            try {
                if (!generateBlocker.isCompleted) {
                    generateBlocker.await()
                }
                onInitFinished()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    cleanupRelease.await()
                }
                throw e
            }
            return LlmExecutionResult(
                responseText = "- Вымышленный центр «Север». [N001]",
                initTimeMs = 200L,
                inferenceTimeMs = 500L,
                backendName = "CPU"
            )
        }
    }

    class SelfCancellingLlmService : LlmInferenceService {
        var generateCallCount = 0
        var normalReturnReached = false

        override suspend fun generateResponse(
            modelFileAbsolutePath: String,
            prompt: String,
            onInitFinished: () -> Unit
        ): LlmExecutionResult {
            generateCallCount++
            currentCoroutineContext()[Job]?.cancel(CancellationException("Self cancelled in service"))
            onInitFinished()
            normalReturnReached = true
            return LlmExecutionResult(
                responseText = "- Вымышленный центр «Север». [N001]",
                initTimeMs = 100L,
                inferenceTimeMs = 200L,
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
    fun testUnsatisfiedLinkErrorHandlingInService() {
        runBlocking {
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

    @Test
    fun testScenarioA_OrchestratorSuccessPipeline() {
        runBlocking {
            val llmService = ControllableSummaryLlmService().apply { generateBlocker.complete(Unit) }
            var fileCheckCount = 0
            var assetLoaderCount = 0
            val statesList = mutableListOf<SummaryUiState>()

            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ ->
                    fileCheckCount++
                    NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile)
                },
                assetLoader = {
                    assetLoaderCount++
                    sampleDataset
                }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { newState ->
                statesList.add(newState)
            }

            assertEquals(1, fileCheckCount)
            assertEquals(1, assetLoaderCount)
            assertEquals(1, llmService.generateCallCount)
            assertTrue(statesList.last() is SummaryUiState.Success)
            val successState = statesList.last() as SummaryUiState.Success
            assertEquals(sampleDataset, successState.dataset)
            assertTrue(successState.summaryResult.isSuccess)
            assertNotNull(successState.executionResult)
            assertTrue(successState.executionResult.initTimeMs > 0L)
        }
    }

    @Test
    fun testScenarioB_TwoConsecutiveSummaryCalls_SecondRejectedWhenCoordinatorBusy() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            var firstCallState: SummaryUiState? = null
            var secondCallRejected = false

            val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestrator.executeSummaryPipeline(validMetadata) { state ->
                    firstCallState = state
                }
            }

            try {
                withTimeout(5000) {
                    llmService.generateStarted.await()

                    assertTrue(coordinator.isBusy())
                    assertEquals(OperationType.SUMMARY, coordinator.activeType())

                    val job2 = coordinator.tryRunOperation(
                        scope = scope,
                        type = OperationType.SUMMARY,
                        onRejected = { secondCallRejected = true }
                    ) {
                        fail("Second SUMMARY call should be rejected while first is running")
                    }

                    assertNull(job2)
                    assertTrue(secondCallRejected)
                    assertEquals(1, llmService.generateCallCount)

                    llmService.generateBlocker.complete(Unit)

                    job1?.join()

                    assertFalse(coordinator.isBusy())
                    assertTrue(firstCallState is SummaryUiState.Success)
                }
            } finally {
                llmService.generateBlocker.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test
    fun testScenarioC_FileLookupThrowsIOException_TerminalErrorStateAndCoordinatorFreedAndSubsequentSuccess() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = ControllableSummaryLlmService().apply { generateBlocker.complete(Unit) }
            var assetLoaderCalled = false
            var finalState: SummaryUiState? = null
            var corruptedCalled = false

            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> throw IOException("Disk read failure") },
                assetLoader = {
                    assetLoaderCalled = true
                    sampleDataset
                }
            )

            val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestrator.executeSummaryPipeline(
                    importedMetadata = validMetadata,
                    onModelCorrupted = { _, _ -> corruptedCalled = true },
                    onStateChange = { state -> finalState = state }
                )
            }

            job1?.join()

            assertFalse(assetLoaderCalled)
            assertEquals(0, llmService.generateCallCount)
            assertFalse(corruptedCalled)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Disk read failure"))
            assertFalse(coordinator.isBusy())

            // Subsequent successful summary run via coordinator
            val orchestratorSuccess = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            var successState: SummaryUiState? = null
            val job2 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestratorSuccess.executeSummaryPipeline(validMetadata) { state -> successState = state }
            }
            job2?.join()

            assertTrue(successState is SummaryUiState.Success)
            assertFalse(coordinator.isBusy())

            scope.cancel()
        }
    }

    @Test
    fun testScenarioD_FileValidationInvalid_TerminalErrorStateAndServiceNotCalled() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            var assetLoaderCalled = false
            var finalState: SummaryUiState? = null
            var corruptedUiState: ModelUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Invalid("File corrupted or missing", "model.bin") },
                assetLoader = {
                    assetLoaderCalled = true
                    sampleDataset
                }
            )

            orchestrator.executeSummaryPipeline(
                importedMetadata = validMetadata,
                onStateChange = { state -> finalState = state },
                onModelCorrupted = { name, reason -> corruptedUiState = ModelUiState.Corrupted(name, reason) }
            )

            assertFalse(assetLoaderCalled)
            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            assertTrue(corruptedUiState is ModelUiState.Corrupted)
            val corrupted = corruptedUiState as ModelUiState.Corrupted
            assertEquals("model.bin", corrupted.originalName)
            assertTrue(corrupted.message.contains("File corrupted or missing"))
        }
    }

    @Test
    fun testScenarioE_AssetOpenIOException_TerminalErrorStateAndServiceNotCalled() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { throw IOException("Asset test_news.json not found") }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Asset test_news.json not found"))
        }
    }

    @Test
    fun testScenarioF_AssetByteLimitExceeded_TerminalErrorStateAndServiceNotCalled() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { throw IllegalArgumentException("Превышен прототипный лимит размера JSON файла (51201 B > 51200 B).") }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Превышен прототипный лимит"))
        }
    }

    @Test
    fun testScenarioG_StrictParserValidationError_TerminalErrorStateAndServiceNotCalled() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { throw IllegalArgumentException("Сообщение N001 имеет некорректную дату/время") }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("некорректную дату/время"))
        }
    }

    @Test
    fun testScenarioH_PromptBuilderThrowsException_TerminalErrorState() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset },
                promptBuilder = { throw IllegalStateException("Prompt builder failure") }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Prompt builder failure"))
        }
    }

    @Test
    fun testScenarioI_LlmServiceThrowsIllegalState_TerminalErrorStateAndCoordinatorFreedAndSubsequentSuccess() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = ControllableSummaryLlmService().apply {
                throwIllegalState = true
                generateBlocker.complete(Unit)
            }
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }
            }
            job1?.join()

            assertEquals(1, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("LLM execution error"))
            assertFalse(coordinator.isBusy())

            // Subsequent successful run
            val orchestratorSuccess = NewsSummaryOrchestrator(
                llmInferenceService = ControllableSummaryLlmService().apply { generateBlocker.complete(Unit) },
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )
            var successState: SummaryUiState? = null
            val job2 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestratorSuccess.executeSummaryPipeline(validMetadata) { state -> successState = state }
            }
            job2?.join()

            assertTrue(successState is SummaryUiState.Success)
            assertFalse(coordinator.isBusy())

            scope.cancel()
        }
    }

    @Test
    fun testScenarioJ_LlmServiceThrowsUnsatisfiedLink_TerminalErrorState() {
        runBlocking {
            val llmService = ControllableSummaryLlmService().apply {
                throwUnsatisfiedLink = true
                generateBlocker.complete(Unit)
            }
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(1, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("UnsatisfiedLinkError") || errorState.message.contains("liblitertlm.so"))
        }
    }

    @Test
    fun testScenarioK_ServiceReturnsEmptyAndWhitespaceResponses_TerminalErrorStateAndNoPostProcessorCall() {
        runBlocking {
            for (emptyText in listOf("", "   \n\t ")) {
                var postProcessorCallCount = 0
                val llmService = ControllableSummaryLlmService().apply {
                    customResponseText = emptyText
                    generateBlocker.complete(Unit)
                }
                val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
                var finalState: SummaryUiState? = null

                val orchestrator = NewsSummaryOrchestrator(
                    llmInferenceService = llmService,
                    activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                    assetLoader = { sampleDataset },
                    postProcessor = { text, ds ->
                        postProcessorCallCount++
                        NewsSummaryPostProcessor.processSummary(text, ds)
                    }
                )

                orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

                assertEquals(1, llmService.generateCallCount)
                assertEquals(0, postProcessorCallCount)
                assertTrue(finalState is SummaryUiState.Error)
                val errorState = finalState as SummaryUiState.Error
                assertTrue(errorState.message.contains("пустой ответ"))
            }
        }
    }

    @Test
    fun testScenarioL_PostProcessorThrowsException_TerminalErrorState() {
        runBlocking {
            val llmService = ControllableSummaryLlmService().apply { generateBlocker.complete(Unit) }
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset },
                postProcessor = { _, _ -> throw IllegalStateException("Postprocessor crash") }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(1, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Postprocessor crash"))
        }
    }

    @Test
    fun testDraftPreservation_MultipleNonBulletLinesInResponse() {
        runBlocking {
            val rawNonBulletText = "Заголовок сводки без маркера\nСтрока 1 без символа\nСтрока 2 без символа"
            val llmService = ControllableSummaryLlmService().apply {
                customResponseText = rawNonBulletText
                generateBlocker.complete(Unit)
            }
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(1, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Success)
            val success = finalState as SummaryUiState.Success
            assertEquals(rawNonBulletText, success.summaryResult.rawResponse)
            assertFalse(success.summaryResult.isSuccess)
            assertFalse(success.summaryResult.isFormatValid)
            assertFalse(success.summaryResult.areCitationsValid)
            assertTrue(success.summaryResult.warningMessages.any { it.contains("Неструктурированная строка") })
        }
    }

    @Test
    fun testDraftPreservation_NonEmptyPointWithUnknownId() {
        runBlocking {
            val rawTextWithUnknownId = "- Описание события. [N999]"
            val llmService = ControllableSummaryLlmService().apply {
                customResponseText = rawTextWithUnknownId
                generateBlocker.complete(Unit)
            }
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }

            assertEquals(1, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Success)
            val success = finalState as SummaryUiState.Success
            assertEquals(rawTextWithUnknownId, success.summaryResult.rawResponse)
            assertFalse(success.summaryResult.areCitationsValid)
            assertEquals(1, success.summaryResult.bulletPoints.size)
            assertFalse(success.summaryResult.bulletPoints[0].isValidCitation)
            assertEquals(emptyList<String>(), success.summaryResult.bulletPoints[0].citedIds)
            assertNotNull(success.summaryResult.bulletPoints[0].warningMessage)
            assertTrue(success.summaryResult.bulletPoints[0].warningMessage!!.contains("N999"))
        }
    }

    @Test
    fun testScenarioM_CancellationDuringAssetLoading_StateIdleAndCancellationReThrown() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val expectedEx = CancellationException("Cancelled during asset load instance")
            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { throw expectedEx }
            )

            var thrownCancellation: CancellationException? = null
            try {
                orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }
            } catch (e: CancellationException) {
                thrownCancellation = e
            }

            assertNotNull(thrownCancellation)
            assertSame(expectedEx, thrownCancellation)
            assertEquals(0, llmService.generateCallCount)
            assertEquals(SummaryUiState.Idle, finalState)
        }
    }

    @Test
    fun testScenarioN_CancellationDuringInference_StateIdleAndCoordinatorHoldsBusyAndRejectsSecondAndSucceedsNext() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = TestNCancellationLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }
            }

            try {
                withTimeout(5000) {
                    llmService.generateStarted.await()

                    assertTrue(coordinator.isBusy())
                    assertEquals(OperationType.SUMMARY, coordinator.activeType())

                    val cancelReason = CancellationException("Cancelled during inference")
                    job1?.cancel(cancelReason)

                    llmService.cleanupEntered.await()

                    assertTrue(coordinator.isBusy())

                    var secondRejected = false
                    val job2 = coordinator.tryRunOperation(
                        scope = scope,
                        type = OperationType.SUMMARY,
                        onRejected = { secondRejected = true }
                    ) {
                        fail("Second summary should be rejected")
                    }
                    assertNull(job2)
                    assertTrue(secondRejected)

                    llmService.cleanupRelease.complete(Unit)

                    job1?.join()

                    assertFalse(coordinator.isBusy())
                    assertEquals(SummaryUiState.Idle, finalState)
                    assertTrue(job1?.isCancelled == true)

                    val orchestratorSuccess = NewsSummaryOrchestrator(
                        llmInferenceService = ControllableSummaryLlmService().apply { generateBlocker.complete(Unit) },
                        activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                        assetLoader = { sampleDataset }
                    )
                    var nextState: SummaryUiState? = null
                    val job3 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                        orchestratorSuccess.executeSummaryPipeline(validMetadata) { state -> nextState = state }
                    }
                    job3?.join()

                    assertTrue(nextState is SummaryUiState.Success)
                    assertFalse(coordinator.isBusy())
                }
            } finally {
                llmService.generateBlocker.complete(Unit)
                llmService.cleanupRelease.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test
    fun testPreparationCancellation_FileCheckAndAssetLoader() {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            runBlocking {
                val llmService = ControllableSummaryLlmService()
                val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }

                // 1. File Check cancellation test
                val fileCheckStarted = CompletableDeferred<Unit>()
                val fileCheckBlocker = CompletableDeferred<Unit>()
                var state1: SummaryUiState? = null

                val orchestrator1 = NewsSummaryOrchestrator(
                    llmInferenceService = llmService,
                    activeFileCheck = { _, _ ->
                        fileCheckStarted.complete(Unit)
                        fileCheckBlocker.await()
                        NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile)
                    },
                    assetLoader = { sampleDataset }
                )

                val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                    orchestrator1.executeSummaryPipeline(validMetadata) { state -> state1 = state }
                }

                try {
                    withTimeout(5000) {
                        fileCheckStarted.await()
                        assertTrue(coordinator.isBusy())
                        job1?.cancel(CancellationException("Cancel file check"))
                        fileCheckBlocker.complete(Unit)
                        job1?.join()

                        assertFalse(coordinator.isBusy())
                        assertEquals(SummaryUiState.Idle, state1)
                        assertTrue(job1?.isCancelled == true)
                        assertEquals(0, llmService.generateCallCount)
                    }
                } finally {
                    fileCheckBlocker.complete(Unit)
                }

                // 2. Asset Loader cancellation test
                val assetStarted = CompletableDeferred<Unit>()
                val assetBlocker = CompletableDeferred<Unit>()
                var state2: SummaryUiState? = null

                val orchestrator2 = NewsSummaryOrchestrator(
                    llmInferenceService = llmService,
                    activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                    assetLoader = {
                        assetStarted.complete(Unit)
                        assetBlocker.await()
                        sampleDataset
                    }
                )

                val job2 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                    orchestrator2.executeSummaryPipeline(validMetadata) { state -> state2 = state }
                }

                try {
                    withTimeout(5000) {
                        assetStarted.await()
                        assertTrue(coordinator.isBusy())
                        job2?.cancel(CancellationException("Cancel asset load"))
                        assetBlocker.complete(Unit)
                        job2?.join()

                        assertFalse(coordinator.isBusy())
                        assertEquals(SummaryUiState.Idle, state2)
                        assertTrue(job2?.isCancelled == true)
                        assertEquals(0, llmService.generateCallCount)
                    }
                } finally {
                    assetBlocker.complete(Unit)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testCancellationDuringFileCheck_BeforeReturningInvalid_NoCorruption() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = ControllableSummaryLlmService()
            var finalState: SummaryUiState? = null
            var corruptedCalled = false
            var invalidReturnReached = false

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ ->
                    currentCoroutineContext()[Job]?.cancel(CancellationException("Cancelled inside file check"))
                    invalidReturnReached = true
                    NewsSummaryOrchestrator.FileValidationResult.Invalid("Corrupted file", "model.bin")
                },
                assetLoader = { sampleDataset }
            )

            val job = scope.launch {
                orchestrator.executeSummaryPipeline(
                    importedMetadata = validMetadata,
                    onModelCorrupted = { _, _ -> corruptedCalled = true },
                    onStateChange = { state -> finalState = state }
                )
            }

            try {
                withTimeout(5000) {
                    job.join()
                    assertTrue(invalidReturnReached)
                    assertFalse(corruptedCalled)
                    assertEquals(SummaryUiState.Idle, finalState)
                    assertTrue(job.isCompleted)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun testServiceReturnAfterCancellation_NoSuccessAndNoPostProcessor() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            var postProcessorCalled = false
            val llmService = SelfCancellingLlmService()
            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset },
                postProcessor = { text, ds ->
                    postProcessorCalled = true
                    NewsSummaryPostProcessor.processSummary(text, ds)
                }
            )

            val job = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                orchestrator.executeSummaryPipeline(validMetadata) { state -> finalState = state }
            }

            try {
                withTimeout(5000) {
                    job?.join()
                    assertTrue(llmService.normalReturnReached)
                    assertFalse(postProcessorCalled)
                    assertFalse(finalState is SummaryUiState.Success)
                    assertEquals(SummaryUiState.Idle, finalState)
                    assertFalse(coordinator.isBusy())
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun testScenarioO_RejectedSummaryWhenImportIsBusy_NoStateChangeAndNoServiceCall() {
        runBlocking {
            val coordinator = OperationCoordinator()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val llmService = ControllableSummaryLlmService()
            val importStarted = CompletableDeferred<Unit>()
            val importBlocker = CompletableDeferred<Unit>()

            val dummyFile = File.createTempFile("model", ".bin").apply { deleteOnExit() }
            var summaryStateChangeCount = 0

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> NewsSummaryOrchestrator.FileValidationResult.Valid(dummyFile) },
                assetLoader = { sampleDataset }
            )

            // 1. Run IMPORT operation
            coordinator.tryRunOperation(scope, OperationType.IMPORT) {
                importStarted.complete(Unit)
                importBlocker.await()
            }

            try {
                withTimeout(5000) {
                    importStarted.await()
                    assertTrue(coordinator.isBusy())
                    assertEquals(OperationType.IMPORT, coordinator.activeType())

                    // 2. Try to run SUMMARY while IMPORT is busy
                    var summaryRejected = false
                    val summaryJob = coordinator.tryRunOperation(
                        scope = scope,
                        type = OperationType.SUMMARY,
                        onRejected = { summaryRejected = true }
                    ) {
                        orchestrator.executeSummaryPipeline(validMetadata) {
                            summaryStateChangeCount++
                        }
                    }

                    assertNull(summaryJob)
                    assertTrue(summaryRejected)
                    assertEquals(0, summaryStateChangeCount)
                    assertEquals(0, llmService.generateCallCount)

                    importBlocker.complete(Unit)
                }
            } finally {
                importBlocker.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test
    fun testScenarioP_NoModelAttachedWhenCoordinatorFree_SetsNoModelError() {
        runBlocking {
            val llmService = ControllableSummaryLlmService()
            var finalState: SummaryUiState? = null

            val orchestrator = NewsSummaryOrchestrator(
                llmInferenceService = llmService,
                activeFileCheck = { _, _ -> throw IllegalStateException("File check should not be called when model is null") },
                assetLoader = { throw IllegalStateException("Asset loader should not be called when model is null") }
            )

            orchestrator.executeSummaryPipeline(null) { state -> finalState = state }

            assertEquals(0, llmService.generateCallCount)
            assertTrue(finalState is SummaryUiState.Error)
            val errorState = finalState as SummaryUiState.Error
            assertTrue(errorState.message.contains("Модель не подключена"))
        }
    }
}
