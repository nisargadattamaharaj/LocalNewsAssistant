package com.example.localnewsassistant.model

import android.util.Log
import com.example.localnewsassistant.BuildConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class LlmExecutionResult(
    val responseText: String,
    val initTimeMs: Long,
    val inferenceTimeMs: Long,
    val backendName: String = "CPU"
)

interface LlmInferenceService {
    suspend fun generateResponse(
        modelFileAbsolutePath: String,
        prompt: String,
        onInitFinished: () -> Unit = {}
    ): LlmExecutionResult
}

class LiteRtLmInferenceService : LlmInferenceService {

    companion object {
        private const val TAG = "LocalNewsLlmTest"
    }

    override suspend fun generateResponse(
        modelFileAbsolutePath: String,
        prompt: String,
        onInitFinished: () -> Unit
    ): LlmExecutionResult {
        return withContext(Dispatchers.IO) {
            val runId = UUID.randomUUID().toString().take(8)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[$runId] Начало запуска LLM. Исходный файл: $modelFileAbsolutePath")
                Log.d(TAG, "[$runId] Длина prompt: ${prompt.length} символов. Текст prompt: '$prompt'")
            }

            val initStartTime = System.nanoTime()

            val initEndTime: Long
            val inferenceStartTime: Long
            val inferenceEndTime: Long
            val textResult: String

            try {
                val engineConfig = EngineConfig(
                    modelPath = modelFileAbsolutePath,
                    backend = Backend.CPU()
                )

                Engine(engineConfig).use { engine ->
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[$runId] Инициализация Engine...")
                    }
                    engine.initialize()
                    initEndTime = System.nanoTime()
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[$runId] Engine успешно инициализирован за ${(initEndTime - initStartTime) / 1_000_000L} мс.")
                    }
                    onInitFinished()

                    val conversationConfig = ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.8
                        )
                    )

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[$runId] Создание Conversation с настроенным SamplerConfig (topK=40, topP=0.95, temp=0.8)...")
                    }
                    engine.createConversation(conversationConfig).use { conversation ->
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[$runId] Вызов conversation.sendMessage(prompt)...")
                        }
                        inferenceStartTime = System.nanoTime()
                        val message: Message = conversation.sendMessage(prompt)
                        inferenceEndTime = System.nanoTime()

                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[$runId] sendMessage завершён за ${(inferenceEndTime - inferenceStartTime) / 1_000_000L} мс.")
                        }

                        textResult = extractMessageText(runId, message)
                    }
                }
            } catch (e: CancellationException) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "[$runId] Операция генерации была отменена (CancellationException).")
                }
                throw e
            } catch (e: UnsatisfiedLinkError) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "[$runId] Ошибка загрузки нативной библиотеки UnsatisfiedLinkError: ${e.message}", e)
                }
                throw UnsatisfiedLinkError("Не удалось загрузить нативную библиотеку LiteRT-LM (UnsatisfiedLinkError): ${e.localizedMessage}")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "[$runId] Исключение во время работы LiteRT-LM: ${e.message}", e)
                }
                throw IllegalStateException("Ошибка выполнения нейросети LiteRT-LM: ${e.localizedMessage}", e)
            }

            val initDurationMs = (initEndTime - initStartTime) / 1_000_000L
            val inferenceDurationMs = (inferenceEndTime - inferenceStartTime) / 1_000_000L

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[$runId] Завершение запуска. Итоговый текст длины ${textResult.length} символов: '$textResult'")
            }

            LlmExecutionResult(
                responseText = textResult,
                initTimeMs = initDurationMs,
                inferenceTimeMs = inferenceDurationMs,
                backendName = "CPU"
            )
        }
    }

    private fun extractMessageText(runId: String, message: Message): String {
        val extractedText = message.toString()

        if (BuildConfig.DEBUG) {
            val roleStr = message.role.toString()
            val contentsList = message.contents.contents
            val contentsCount = contentsList.size
            val channelsMap = message.channels

            Log.d(TAG, "[$runId] === ДИАГНОСТИКА СТРУКТУРЫ СТАДИИ MESSAGE ===")
            Log.d(TAG, "[$runId] Role: $roleStr")
            Log.d(TAG, "[$runId] Message.toString() [длина=${extractedText.length}]: '$extractedText'")
            Log.d(TAG, "[$runId] Contents количество элементов: $contentsCount")

            contentsList.forEachIndexed { index, content ->
                when (content) {
                    is com.google.ai.edge.litertlm.Content.Text -> {
                        val t = content.text
                        Log.d(TAG, "[$runId]  - Content[$index] (Text) [длина=${t.length}]: '$t'")
                    }
                    else -> {
                        Log.d(TAG, "[$runId]  - Content[$index] (Type=${content.javaClass.simpleName})")
                    }
                }
            }

            if (channelsMap.isNotEmpty()) {
                Log.d(TAG, "[$runId] Channels (${channelsMap.size}): $channelsMap")
            } else {
                Log.d(TAG, "[$runId] Channels: [пусто]")
            }

            Log.d(TAG, "[$runId] Текст для UI [длина=${extractedText.length}]: '$extractedText'")
            Log.d(TAG, "[$runId] =========================================")
        }

        return extractedText
    }
}
