package com.example.localnewsassistant.data.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NewsCountReader(
    private val daoProvider: () -> NewsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun getTotalCount(): Int = withContext(ioDispatcher) {
        val dao = daoProvider()
        dao.getTotalCount()
    }
}
