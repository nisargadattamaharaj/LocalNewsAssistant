package com.example.localnewsassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface NewsCountUiState {
    object NotRequested : NewsCountUiState
    data class Loading(val lastSuccessfulCount: Int? = null) : NewsCountUiState
    data class Success(val count: Int) : NewsCountUiState
    data class Error(val message: String, val lastSuccessfulCount: Int? = null) : NewsCountUiState
}

class NewsCountViewModel(
    private val fetchCount: suspend () -> Int
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewsCountUiState>(NewsCountUiState.NotRequested)
    val uiState: StateFlow<NewsCountUiState> = _uiState.asStateFlow()

    private var lastSuccessfulCount: Int? = null

    fun fetchNewsCount() {
        // Synchronous guard and Loading transition on Main thread before launch
        if (_uiState.value is NewsCountUiState.Loading) {
            return
        }
        _uiState.value = NewsCountUiState.Loading(lastSuccessfulCount)

        viewModelScope.launch {
            try {
                val resultCount = fetchCount()
                // Verify coroutine context is still active before accepting result
                coroutineContext.ensureActive()
                lastSuccessfulCount = resultCount
                _uiState.value = NewsCountUiState.Success(resultCount)
            } catch (e: CancellationException) {
                // Restore previous state or NotRequested on cancellation
                _uiState.value = lastSuccessfulCount?.let { NewsCountUiState.Success(it) }
                    ?: NewsCountUiState.NotRequested
                throw e
            } catch (e: Exception) {
                // If coroutine was cancelled when dependency threw an exception, process as cancellation
                if (!coroutineContext.isActive) {
                    _uiState.value = lastSuccessfulCount?.let { NewsCountUiState.Success(it) }
                        ?: NewsCountUiState.NotRequested
                    return@launch
                }
                val safeUserMessage = "Не удалось получить количество записей"
                _uiState.value = NewsCountUiState.Error(
                    message = safeUserMessage,
                    lastSuccessfulCount = lastSuccessfulCount
                )
            }
        }
    }
}

class NewsCountViewModelFactory(
    private val fetchCount: suspend () -> Int
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NewsCountViewModel::class.java)) {
            return NewsCountViewModel(fetchCount) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
