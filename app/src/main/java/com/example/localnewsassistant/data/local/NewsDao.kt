package com.example.localnewsassistant.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
abstract class NewsDao {

    @Upsert
    protected abstract suspend fun insertOrUpdateEntities(entities: List<StoredNewsEntity>)

    open suspend fun upsertAll(entities: List<StoredNewsEntity>) {
        val preparedEntities = entities.map { entity ->
            entity.copy(normalizedText = SearchTextNormalizer.normalize(entity.text))
        }
        insertOrUpdateEntities(preparedEntities)
    }

    @Query("SELECT * FROM stored_news WHERE datasetId = :datasetId ORDER BY messageId ASC")
    abstract suspend fun getByDatasetId(datasetId: String): List<StoredNewsEntity>

    @Query("SELECT * FROM stored_news WHERE datasetId = :datasetId AND messageId = :messageId")
    abstract suspend fun getByCompositeKey(datasetId: String, messageId: String): StoredNewsEntity?

    @Query("SELECT COUNT(*) FROM stored_news")
    abstract suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM stored_news WHERE datasetId = :datasetId")
    abstract suspend fun getCountByDatasetId(datasetId: String): Int

    @Query("""
        SELECT * FROM stored_news
        WHERE normalizedText LIKE '%' || :escapedQuery || '%' ESCAPE '\'
        ORDER BY datasetId ASC, messageId ASC
        LIMIT 50
    """)
    abstract suspend fun searchByText(escapedQuery: String): List<StoredNewsEntity>
}
