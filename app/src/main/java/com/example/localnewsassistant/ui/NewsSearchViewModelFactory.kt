package com.example.localnewsassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.localnewsassistant.data.local.StoredNewsEntity

class NewsSearchViewModelFactory(
    private val searchNews: suspend (String) -> List<StoredNewsEntity>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NewsSearchViewModel::class.java)) {
            return NewsSearchViewModel(searchNews) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
