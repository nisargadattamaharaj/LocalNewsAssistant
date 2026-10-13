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
import com.example.localnewsassistant.model.TestPromptOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

class ModelImportViewModel @JvmOverloads constructor(
    application: Application,
    llmInferenceService: LlmInferenceService = LiteRtLmInferenceService()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ModelUiState>(ModelUiState.Loading)
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    private val llmRunner = LlmExecutionRunner(llmInferenceService)
    val llmState: StateFlow<LlmExecutionState> = llmRunner.llmState

    private val _selectedPromptOption = MutableStateFlow(TestPromptOption.OPTION_A)
    val selectedPromptOption: StateFlow<TestPromptOption> = _selectedPromptOption.asStateFlow()

    init {
        loadSavedModel()
    }

    fun selectPromptOption(option: TestPromptOption) {
        val currentLlmState = llmState.value
        if (currentLlmState is LlmExecutionState.Initializing || currentLlmState is LlmExecutionState.Generating) {
            return
        }
        _selectedPromptOption.value = option
    }

    fun loadSavedModel() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ModelUiState.Loading

            val loadResult = ModelStorageManager.loadMetadata(app)
            val activeFileName = (loadResult as? ModelLoadResult.Valid)?.metadata?.internalFileName

            ModelStorageManager.cleanTempFiles(app, activeFileName)

            _uiState.value = when (loadResult) {
                is ModelLoadResult.NoModel -> ModelUiState.NoModel
                is ModelLoadResult.Valid -> ModelUiState.Imported(loadResult.metadata)
                is ModelLoadResult.Corrupted -> ModelUiState.Corrupted(
                    originalName = loadResult.originalName,
                    message = loadResult.message
                )
            }
        }
    }

    fun importModel(uri: Uri) {
        val currentState = _uiState.value
        val currentLlmState = llmState.value

        if (currentState is ModelUiState.Loading ||
            currentState is ModelUiState.Importing ||
            currentState is ModelUiState.IndeterminateError ||
            currentLlmState is LlmExecutionState.Initializing ||
            currentLlmState is LlmExecutionState.Generating
        ) {
            return
        }

        val app = getApplication<Application>()

        val previousMetadata = when (currentState) {
            is ModelUiState.Imported -> currentState.metadata
            is ModelUiState.Error -> currentState.previousMetadata
            else -> null
        }

        _uiState.value = ModelUiState.Importing(
            copiedBytes = 0L,
            totalBytes = -1L,
            originalName = "Запрос документа..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            val activeFileName = previousMetadata?.internalFileName
            try {
                val (displayName, totalSize) = ModelStorageManager.queryUriDetails(app, uri)
                val initialName = displayName ?: ""

                _uiState.value = ModelUiState.Importing(
                    copiedBytes = 0L,
                    totalBytes = totalSize,
                    originalName = if (initialName.isNotBlank()) initialName else "Файл модели"
                )

                val metadata = ModelStorageManager.copyModelStream(
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

                // Reset previous LLM state on successful model import
                llmRunner.resetState()
                _uiState.value = ModelUiState.Imported(metadata)

            } catch (e: CancellationException) {
                ModelStorageManager.cleanTempFiles(app, activeFileName)
                _uiState.value = if (previousMetadata != null) {
                    ModelUiState.Imported(previousMetadata)
                } else {
                    ModelUiState.NoModel
                }
                throw e
            } catch (e: IndeterminateStorageException) {
                // Restore IndeterminateError handling! Do NOT clean up temp files!
                _uiState.value = ModelUiState.IndeterminateError(
                    message = e.localizedMessage ?: "Неопределённое состояние хранилища метаданных"
                )
            } catch (e: Exception) {
                ModelStorageManager.cleanTempFiles(app, activeFileName)
                val userMsg = e.localizedMessage ?: "Не удалось скопировать файл модели"
                _uiState.value = ModelUiState.Error(
                    message = userMsg,
                    previousMetadata = previousMetadata
                )
            }
        }
    }

    fun runLlmTest() {
        val currentImportState = _uiState.value
        val currentLlmState = llmState.value

        if (currentImportState is ModelUiState.Loading ||
            currentImportState is ModelUiState.Importing ||
            currentImportState is ModelUiState.IndeterminateError ||
            currentLlmState is LlmExecutionState.Initializing ||
            currentLlmState is LlmExecutionState.Generating
        ) {
            return
        }

        val importedMetadata = (currentImportState as? ModelUiState.Imported)?.metadata
        if (importedMetadata == null) {
            return
        }

        val app = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            val activeFile = ModelStorageManager.getActiveModelFile(app, importedMetadata.internalFileName)

            if (!activeFile.exists() || !activeFile.isFile || activeFile.length() <= 0L || activeFile.length() != importedMetadata.sizeBytes) {
                _uiState.value = ModelUiState.Corrupted(
                    originalName = importedMetadata.originalName,
                    message = "Файл импортированной модели «${importedMetadata.originalName}» не найден во внутреннем хранилище или повреждён. Требуется повторный импорт."
                )
                return@launch
            }

            llmRunner.runLlm(activeFile.absolutePath, _selectedPromptOption.value)
        }
    }
}
