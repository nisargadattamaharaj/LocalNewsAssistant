package com.example.localnewsassistant.model

import com.example.localnewsassistant.ui.SummaryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

class NewsSummaryOrchestrator(
    private val llmInferenceService: LlmInferenceService,
    private val activeFileCheck: suspend (internalFileName: String, expectedBytes: Long) -> FileValidationResult,
    private val assetLoader: suspend (assetName: String) -> SyntheticNewsDataset,
    private val promptBuilder: (dataset: SyntheticNewsDataset) -> String = { NewsSummaryPromptBuilder.buildPrompt(it) },
    private val postProcessor: (rawResponseText: String, dataset: SyntheticNewsDataset) -> NewsSummaryResult = { text, ds -> NewsSummaryPostProcessor.processSummary(text, ds) }
) {

    sealed interface FileValidationResult {
        data class Valid(val file: File) : FileValidationResult
        data class Invalid(val reason: String, val originalName: String? = null) : FileValidationResult
    }

    suspend fun executeSummaryPipeline(
        importedMetadata: ModelMetadata?,
        onModelCorrupted: ((originalName: String?, reason: String) -> Unit)? = null,
        onStateChange: (SummaryUiState) -> Unit
    ) {
        try {
            currentCoroutineContext().ensureActive()
            if (importedMetadata == null) {
                onStateChange(SummaryUiState.Error("Модель не подключена. Сначала импортируйте файл модели."))
                return
            }

            onStateChange(SummaryUiState.PreparingData)
            currentCoroutineContext().ensureActive()

            val fileValidation = activeFileCheck(importedMetadata.internalFileName, importedMetadata.sizeBytes)
            currentCoroutineContext().ensureActive()

            if (fileValidation is FileValidationResult.Invalid) {
                val origName = fileValidation.originalName ?: importedMetadata.originalName
                onModelCorrupted?.invoke(origName, fileValidation.reason)
                onStateChange(SummaryUiState.Error(fileValidation.reason))
                return
            }

            val activeFile = (fileValidation as FileValidationResult.Valid).file

            val dataset = assetLoader("test_news.json")
            currentCoroutineContext().ensureActive()

            val prompt = promptBuilder(dataset)
            currentCoroutineContext().ensureActive()

            onStateChange(SummaryUiState.Initializing)

            val executionResult = llmInferenceService.generateResponse(
                modelFileAbsolutePath = activeFile.absolutePath,
                prompt = prompt,
                onInitFinished = {
                    onStateChange(SummaryUiState.Generating)
                }
            )

            currentCoroutineContext().ensureActive()

            if (executionResult.responseText.isBlank() || executionResult.responseText.all { it.isWhitespace() }) {
                onStateChange(SummaryUiState.Error("Модель вернула пустой ответ при составлении сводки."))
                return
            }

            val processedResult = postProcessor(
                executionResult.responseText,
                dataset
            )

            currentCoroutineContext().ensureActive()

            onStateChange(
                SummaryUiState.Success(
                    dataset = dataset,
                    summaryResult = processedResult,
                    executionResult = executionResult
                )
            )

        } catch (e: CancellationException) {
            onStateChange(SummaryUiState.Idle)
            throw e
        } catch (e: UnsatisfiedLinkError) {
            val userMsg = e.localizedMessage ?: "Не удалось загрузить нативную библиотеку LiteRT-LM (UnsatisfiedLinkError)"
            onStateChange(SummaryUiState.Error(userMsg))
        } catch (e: Exception) {
            val userMsg = e.localizedMessage ?: "Ошибка выполнения генерации сводки"
            onStateChange(SummaryUiState.Error(userMsg))
        }
    }
}
