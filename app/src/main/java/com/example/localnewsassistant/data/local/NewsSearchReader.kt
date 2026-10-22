package com.example.localnewsassistant.data.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class NewsSearchReader(
    private val daoProvider: () -> NewsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun search(query: String): List<StoredNewsEntity> {
        currentCoroutineContext().ensureActive()

        val trimmedQuery = query.trim()

        if (trimmedQuery.isEmpty()) {
            return emptyList()
        }

        if (trimmedQuery.length > 256) {
            throw IllegalArgumentException("Поисковый запрос превышает допустимую длину 256 символов")
        }

        if (trimmedQuery.contains('\u0000')) {
            throw IllegalArgumentException("Поисковый запрос содержит недопустимый символ NUL")
        }

        val normalizedQuery = SearchTextNormalizer.normalize(trimmedQuery)
        val escapedQuery = sanitizeQueryForLike(normalizedQuery)

        val results = withContext(ioDispatcher) {
            val dao = daoProvider()
            dao.searchByText(escapedQuery)
        }

        currentCoroutineContext().ensureActive()
        return results
    }

    companion object {
        fun sanitizeQueryForLike(input: String): String {
            val sb = StringBuilder(input.length + 8)
            for (ch in input) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '%' -> sb.append("\\%")
                    '_' -> sb.append("\\_")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
