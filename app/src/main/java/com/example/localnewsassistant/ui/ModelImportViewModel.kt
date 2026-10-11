package com.example.localnewsassistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localnewsassistant.model.IndeterminateStorageException
import com.example.localnewsassistant.model.ModelLoadResult
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.ModelStorageManager
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

class ModelImportViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ModelUiState>(ModelUiState.Loading)
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init {
        loadSavedModel()
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
        // Lock operation immediately on calling thread before any async query
        if (currentState is ModelUiState.Loading || currentState is ModelUiState.Importing || currentState is ModelUiState.IndeterminateError) {
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
                // Query document details EXACTLY ONCE on Dispatchers.IO inside catch block
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
                // Do NOT clean up files when storage state is indeterminate
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
}
