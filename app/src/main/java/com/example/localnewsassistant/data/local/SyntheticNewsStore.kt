package com.example.localnewsassistant.data.local

import androidx.room.withTransaction
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsValidator

class SyntheticNewsStore(
    private val database: NewsDatabase
) {
    suspend fun storeDataset(dataset: SyntheticNewsDataset) {
        // 1. Full validation before any write
        val validatedDataset = SyntheticNewsValidator.validateDataset(dataset)

        // 2. Map dataset messages to entities
        val entities = validatedDataset.messages.map { msg ->
            StoredNewsEntity(
                datasetId = validatedDataset.datasetId,
                messageId = msg.id,
                source = msg.source,
                publishedAt = msg.publishedAt,
                text = msg.text,
                synthetic = validatedDataset.synthetic
            )
        }

        // 3. Single atomic batch transaction
        database.withTransaction {
            database.newsDao().upsertAll(entities)
        }
    }
}
