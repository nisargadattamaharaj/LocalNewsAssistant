package com.example.localnewsassistant.ui

import com.example.localnewsassistant.data.local.StoredNewsEntity

sealed interface NewsSearchUiState {
    object NotRequested : NewsSearchUiState
    data class Loading(val query: String) : NewsSearchUiState
    data class Success(
        val query: String,
        val results: List<StoredNewsEntity>
    ) : NewsSearchUiState
    data class EmptyResult(val query: String) : NewsSearchUiState
    data class Error(
        val query: String,
        val message: String
    ) : NewsSearchUiState
}
