package com.example.localnewsassistant.model

import com.example.localnewsassistant.ui.LlmExecutionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class TestPromptOption(val label: String, val promptText: String) {
    OPTION_A(
        label = "А: Русская инструкция",
        promptText = "Ответь по-русски одним коротким предложением: что такое локальная языковая модель?"
    ),
    OPTION_B(
        label = "Б: Русский счет (2+2)",
        promptText = "Сколько будет 2 + 2? Ответь только числом."
    ),
    OPTION_C(
        label = "В: Английский счет (2+2)",
        promptText = "What is 2 + 2? Reply with only the number."
    )
}

class LlmExecutionRunner(
    private val llmService: LlmInferenceService = LiteRtLmInferenceService()
) {
    private val _llmState = MutableStateFlow<LlmExecutionState>(LlmExecutionState.Idle)
    val llmState: StateFlow<LlmExecutionState> = _llmState.asStateFlow()

    fun resetState() {
        _llmState.value = LlmExecutionState.Idle
    }

    suspend fun runLlm(
        activeFileAbsolutePath: String,
        promptOption: TestPromptOption = TestPromptOption.OPTION_A
    ) {
        val currentState = _llmState.value
        if (currentState is LlmExecutionState.Initializing || currentState is LlmExecutionState.Generating) {
            return
        }

        _llmState.value = LlmExecutionState.Initializing

        withContext(Dispatchers.IO) {
            try {
                val result = llmService.generateResponse(
                    modelFileAbsolutePath = activeFileAbsolutePath,
                    prompt = promptOption.promptText,
                    onInitFinished = {
                        _llmState.value = LlmExecutionState.Generating
                    }
                )

                if (result.responseText.isBlank()) {
                    _llmState.value = LlmExecutionState.Error("Модель вернула пустой ответ.")
                } else {
                    _llmState.value = LlmExecutionState.Success(result)
                }
            } catch (e: CancellationException) {
                _llmState.value = LlmExecutionState.Idle
                throw e
            } catch (e: UnsatisfiedLinkError) {
                _llmState.value = LlmExecutionState.Error(
                    e.localizedMessage ?: "Не удалось загрузить нативную библиотеку LiteRT-LM (UnsatisfiedLinkError)"
                )
            } catch (e: Exception) {
                _llmState.value = LlmExecutionState.Error(
                    e.localizedMessage ?: "Ошибка запуска нейросети"
                )
            }
        }
    }
}
