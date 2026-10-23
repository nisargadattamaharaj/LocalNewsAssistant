package com.example.localnewsassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NewsSearchViewModel(
    private val searchNews: suspend (String) -> List<StoredNewsEntity>
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewsSearchUiState>(NewsSearchUiState.NotRequested)
    val uiState: StateFlow<NewsSearchUiState> = _uiState.asStateFlow()

    private var currentRequestId: Long = 0L
    private var currentSearchJob: Job? = null

    fun performSearch(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return
        }

        // 1. Synchronously increment request ID and cancel previous search job
        val requestId = ++currentRequestId
        currentSearchJob?.cancel()

        // 2. Synchronously transition state to Loading for new query
        _uiState.value = NewsSearchUiState.Loading(query = trimmedQuery)

        // 3. Launch search job in viewModelScope
        currentSearchJob = viewModelScope.launch {
            try {
                val results = searchNews(trimmedQuery)

                coroutineContext.ensureActive()
                if (requestId != currentRequestId) return@launch

                if (results.isEmpty()) {
                    _uiState.value = NewsSearchUiState.EmptyResult(query = trimmedQuery)
                } else {
                    _uiState.value = NewsSearchUiState.Success(
                        query = trimmedQuery,
                        results = results
                    )
                }
            } catch (e: CancellationException) {
                if (requestId == currentRequestId && viewModelScope.isActive) {
                    _uiState.value = NewsSearchUiState.NotRequested
                }
                throw e
            } catch (_: IllegalArgumentException) {
                if (requestId != currentRequestId || !viewModelScope.isActive) return@launch
                if (!isActive) {
                    _uiState.value = NewsSearchUiState.NotRequested
                    return@launch
                }
                _uiState.value = NewsSearchUiState.Error(
                    query = trimmedQuery,
                    message = "Недопустимый поисковый запрос"
                )
            } catch (_: Exception) {
                if (requestId != currentRequestId || !viewModelScope.isActive) return@launch
                if (!isActive) {
                    _uiState.value = NewsSearchUiState.NotRequested
                    return@launch
                }
                _uiState.value = NewsSearchUiState.Error(
                    query = trimmedQuery,
                    message = "Не удалось выполнить локальный поиск"
                )
            }
        }
    }
}
