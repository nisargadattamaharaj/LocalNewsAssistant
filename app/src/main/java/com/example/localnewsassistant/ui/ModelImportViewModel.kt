package com.example.localnewsassistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localnewsassistant.model.IndeterminateStorageException
import com.example.localnewsassistant.model.LiteRtLmInferenceService
import com.example.localnewsassistant.model.LlmExecutionResult
import com.example.localnewsassistant.model.LlmExecutionRunner
import com.example.localnewsassistant.model.LlmInferenceService
import com.example.localnewsassistant.model.ModelLoadResult
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.ModelStorageManager
import com.example.localnewsassistant.model.NewsSummaryPostProcessor
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.NewsSummaryResult
import com.example.localnewsassistant.model.OperationCoordinator
import com.example.localnewsassistant.model.OperationType
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsParser
import com.example.localnewsassistant.model.TestPromptOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface ModelUiState {
    object Loading : ModelUiState

    object NoModel : ModelUiState

    data class Importing(
        val copiedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val originalName: String = ""
    ) : ModelUiState

    data class Imported(
        val metadata: ModelMetadata,
        val lastError: String? = null
    ) : ModelUiState

    data class Corrupted(
        val originalName: String?,
        val message: String
    ) : ModelUiState

    data class IndeterminateError(
        val message: String
    ) : ModelUiState

    data class Error(
        val message: String,
        val previousMetadata: ModelMetadata? = null
    ) : ModelUiState
}

sealed interface LlmExecutionState {
    object Idle : LlmExecutionState
    object Initializing : LlmExecutionState
    object Generating : LlmExecutionState
    data class Success(val result: LlmExecutionResult) : LlmExecutionState
    data class Error(val message: String) : LlmExecutionState
}

sealed interface SummaryUiState {
    object Idle : SummaryUiState
    object PreparingData : SummaryUiState
    object Initializing : SummaryUiState
    object Generating : SummaryUiState
    data class Success(
        val dataset: SyntheticNewsDataset,
        val summaryResult: NewsSummaryResult,
        val executionResult: LlmExecutionResult
    ) : SummaryUiState
    data class Error(val message: String) : SummaryUiState
}

object ModelImportFinalizer {
    suspend fun handleImportCancellation(
        cancellationException: CancellationException,
        loadMetadata: suspend () -> ModelLoadResult,
        cleanTempFiles: suspend (activeFileName: String?) -> Unit,
        updateUiState: (ModelUiState) -> Unit
    ) {
        withContext(NonCancellable) {
            val loadResult = try {
                loadMetadata()
            } catch (e: IndeterminateStorageException) {
                ModelLoadResult.Corrupted(
                    originalName = null,
                    expectedSize = null,
                    message = e.localizedMessage ?: "Неопределённое состояние хранилища"
                )
            } catch (e: Exception) {
                ModelLoadResult.Corrupted(
                    originalName = null,
                    expectedSize = null,
                    message = e.localizedMessage ?: "Ошибка чтения метаданных при отмене"
                )
            }

            when (loadResult) {
                is ModelLoadResult.Valid -> {
                    var cleanupException: Exception? = null
                    try {
                        cleanTempFiles(loadResult.metadata.internalFileName)
                    } catch (e: Exception) {
                        cleanupException = e
                    }

                    if (cleanupException != null) {
                        cancellationException.addSuppressed(cleanupException)
                        updateUiState(
                            ModelUiState.Error(
                                message = "Импорт отменён, но произошла ошибка очистки временных файлов: ${cleanupException.localizedMessage}",
                                previousMetadata = loadResult.metadata
                            )
                        )
                    } else {
                        updateUiState(ModelUiState.Imported(loadResult.metadata))
                    }
                }

                is ModelLoadResult.NoModel -> {
                    var cleanupException: Exception? = null
                    try {
                        cleanTempFiles(null)
                    } catch (e: Exception) {
                        cleanupException = e
                    }

                    if (cleanupException != null) {
                        cancellationException.addSuppressed(cleanupException)
                        updateUiState(
                            ModelUiState.Error(
                                message = "Импорт отменён, но произошла ошибка очистки временных файлов: ${cleanupException.localizedMessage}"
                            )
                        )
                    } else {
                        updateUiState(ModelUiState.NoModel)
                    }
                }

                is ModelLoadResult.Corrupted -> {
                    // Do NOT call cleanTempFiles!
                    updateUiState(
                        ModelUiState.IndeterminateError(
                            message = loadResult.message
                        )
                    )
                }
            }
        }

        throw cancellationException
    }
}

class ModelImportViewModel @JvmOverloads constructor(
    application: Application,
    private val llmInferenceService: LlmInferenceService = LiteRtLmInferenceService(),
    val coordinator: OperationCoordinator = OperationCoordinator()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ModelUiState>(ModelUiState.Loading)
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    private val llmRunner = LlmExecutionRunner(llmInferenceService)
    val llmState: StateFlow<LlmExecutionState> = llmRunner.llmState

    private val _summaryState = MutableStateFlow<SummaryUiState>(SummaryUiState.Idle)
    val summaryState: StateFlow<SummaryUiState> = _summaryState.asStateFlow()

    private val _selectedPromptOption = MutableStateFlow(TestPromptOption.OPTION_A)
    val selectedPromptOption: StateFlow<TestPromptOption> = _selectedPromptOption.asStateFlow()

    val isBusy: StateFlow<Boolean> = coordinator.isBusy

    init {
        loadSavedModel()
    }

    fun selectPromptOption(option: TestPromptOption) {
        if (coordinator.isBusy()) {
            return
        }
        _selectedPromptOption.value = option
    }

    fun loadSavedModel() {
        coordinator.tryRunOperation(viewModelScope, OperationType.RECOVERY) {
            _uiState.value = ModelUiState.Loading

            try {
                val app = getApplication<Application>()
                val loadResult = withContext(Dispatchers.IO) {
                    ModelStorageManager.loadMetadata(app)
                }
                val activeFileName = (loadResult as? ModelLoadResult.Valid)?.metadata?.internalFileName

                withContext(Dispatchers.IO) {
                    ModelStorageManager.cleanTempFiles(app, activeFileName)
                }

                _uiState.value = when (loadResult) {
                    is ModelLoadResult.NoModel -> ModelUiState.NoModel
                    is ModelLoadResult.Valid -> ModelUiState.Imported(loadResult.metadata)
                    is ModelLoadResult.Corrupted -> ModelUiState.Corrupted(
                        originalName = loadResult.originalName,
                        message = loadResult.message
                    )
                }
            } catch (e: CancellationException) {
                _uiState.value = ModelUiState.NoModel
                throw e
            } catch (e: Exception) {
                _uiState.value = ModelUiState.Error(e.localizedMessage ?: "Не удалось восстановить хранилище")
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_uiState.value is ModelUiState.IndeterminateError) {
            return
        }

        val currentState = _uiState.value
        val previousMetadata = when (currentState) {
            is ModelUiState.Imported -> currentState.metadata
            is ModelUiState.Error -> currentState.previousMetadata
            else -> null
        }

        coordinator.tryRunOperation(
            viewModelScope,
            OperationType.IMPORT
        ) {
            _uiState.value = ModelUiState.Importing(
                copiedBytes = 0L,
                totalBytes = -1L,
                originalName = "Запрос документа..."
            )

            val app = getApplication<Application>()

            try {
                val (displayName, totalSize) = withContext(Dispatchers.IO) {
                    ModelStorageManager.queryUriDetails(app, uri)
                }
                val initialName = displayName ?: ""

                _uiState.value = ModelUiState.Importing(
                    copiedBytes = 0L,
                    totalBytes = totalSize,
                    originalName = if (initialName.isNotBlank()) initialName else "Файл модели"
                )

                val metadata = withContext(Dispatchers.IO) {
                    ModelStorageManager.copyModelStream(
                        context = app,
                        uri = uri,
                        displayName = initialName,
                        totalSize = totalSize,
                        currentMetadata = previousMetadata,
                        onProgress = { copied, total ->
                            _uiState.value = ModelUiState.Importing(
                                copiedBytes = copied,
                                totalBytes = if (total > 0) total else totalSize,
                                originalName = if (initialName.isNotBlank()) initialName else "Файл модели"
                            )
                        }
                    )
                }

                llmRunner.resetState()
                _summaryState.value = SummaryUiState.Idle
                _uiState.value = ModelUiState.Imported(metadata)

            } catch (e: CancellationException) {
                ModelImportFinalizer.handleImportCancellation(
                    cancellationException = e,
                    loadMetadata = { ModelStorageManager.loadMetadata(app) },
                    cleanTempFiles = { activeFileName -> ModelStorageManager.cleanTempFiles(app, activeFileName) },
                    updateUiState = { newState -> _uiState.value = newState }
                )
            } catch (e: IndeterminateStorageException) {
                _uiState.value = ModelUiState.IndeterminateError(
                    message = e.localizedMessage ?: "Неопределённое состояние хранилища метаданных"
                )
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    val activeFileName = (ModelStorageManager.loadMetadata(app) as? ModelLoadResult.Valid)?.metadata?.internalFileName
                    ModelStorageManager.cleanTempFiles(app, activeFileName)
                }
                val userMsg = e.localizedMessage ?: "Не удалось скопировать файл модели"
                _uiState.value = ModelUiState.Error(
                    message = userMsg,
                    previousMetadata = previousMetadata
                )
            }
        }
    }

    fun runLlmTest() {
        if (_uiState.value is ModelUiState.IndeterminateError) {
            return
        }

        val currentImportState = _uiState.value
        val importedMetadata = (currentImportState as? ModelUiState.Imported)?.metadata ?: return

        coordinator.tryRunOperation(viewModelScope, OperationType.LLM_TEST) {
            val app = getApplication<Application>()

            val activeFile = withContext(Dispatchers.IO) {
                ModelStorageManager.getActiveModelFile(app, importedMetadata.internalFileName)
            }

            val isFileValid = withContext(Dispatchers.IO) {
                activeFile.exists() && activeFile.isFile && activeFile.length() > 0L && activeFile.length() == importedMetadata.sizeBytes
            }

            if (!isFileValid) {
                _uiState.value = ModelUiState.Corrupted(
                    originalName = importedMetadata.originalName,
                    message = "Файл импортированной модели «${importedMetadata.originalName}» не найден во внутреннем хранилище или повреждён. Требуется повторный импорт."
                )
                return@tryRunOperation
            }

            llmRunner.runLlm(activeFile.absolutePath, _selectedPromptOption.value)
        }
    }

    fun runNewsSummary() {
        if (_uiState.value is ModelUiState.IndeterminateError) {
            return
        }

        val currentImportState = _uiState.value
        val importedMetadata = (currentImportState as? ModelUiState.Imported)?.metadata
        if (importedMetadata == null) {
            _summaryState.value = SummaryUiState.Error("Модель не подключена. Сначала импортируйте файл модели.")
            return
        }

        coordinator.tryRunOperation(viewModelScope, OperationType.SUMMARY) {
            _summaryState.value = SummaryUiState.PreparingData

            val app = getApplication<Application>()

            val activeFile = withContext(Dispatchers.IO) {
                ModelStorageManager.getActiveModelFile(app, importedMetadata.internalFileName)
            }

            val isFileValid = withContext(Dispatchers.IO) {
                activeFile.exists() && activeFile.isFile && activeFile.length() > 0L && activeFile.length() == importedMetadata.sizeBytes
            }

            if (!isFileValid) {
                _summaryState.value = SummaryUiState.Error("Файл импортированной модели «${importedMetadata.originalName}» не найден во внутреннем хранилище или повреждён. Требуется повторный импорт.")
                return@tryRunOperation
            }

            val dataset = try {
                withContext(Dispatchers.IO) {
                    com.example.localnewsassistant.model.SyntheticNewsAssetLoader.loadAndValidateAsset(app, "test_news.json")
                }
            } catch (e: CancellationException) {
                _summaryState.value = SummaryUiState.Idle
                throw e
            } catch (e: Exception) {
                _summaryState.value = SummaryUiState.Error("Ошибка загрузки/валидации новостных данных: ${e.localizedMessage}")
                return@tryRunOperation
            }

            val prompt = NewsSummaryPromptBuilder.buildPrompt(dataset)
            _summaryState.value = SummaryUiState.Initializing

            try {
                val executionResult = llmInferenceService.generateResponse(
                    modelFileAbsolutePath = activeFile.absolutePath,
                    prompt = prompt,
                    onInitFinished = {
                        _summaryState.value = SummaryUiState.Generating
                    }
                )

                if (executionResult.responseText.isBlank()) {
                    _summaryState.value = SummaryUiState.Error("Модель вернула пустой ответ при составлении сводки.")
                    return@tryRunOperation
                }

                val processedResult = NewsSummaryPostProcessor.processSummary(
                    rawResponseText = executionResult.responseText,
                    dataset = dataset
                )

                _summaryState.value = SummaryUiState.Success(
                    dataset = dataset,
                    summaryResult = processedResult,
                    executionResult = executionResult
                )

            } catch (e: CancellationException) {
                _summaryState.value = SummaryUiState.Idle
                throw e
            } catch (e: UnsatisfiedLinkError) {
                val userMsg = e.localizedMessage ?: "Не удалось загрузить нативную библиотеку LiteRT-LM (UnsatisfiedLinkError)"
                _summaryState.value = SummaryUiState.Error(userMsg)
            } catch (e: Exception) {
                val userMsg = e.localizedMessage ?: "Ошибка выполнения генерации сводки"
                _summaryState.value = SummaryUiState.Error(userMsg)
            }
        }
    }
}
