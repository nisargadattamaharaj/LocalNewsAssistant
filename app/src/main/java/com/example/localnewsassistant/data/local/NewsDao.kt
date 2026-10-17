package com.example.localnewsassistant.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface NewsDao {

    @Upsert
    suspend fun upsertAll(entities: List<StoredNewsEntity>)

    @Query("SELECT * FROM stored_news WHERE datasetId = :datasetId ORDER BY messageId ASC")
    suspend fun getByDatasetId(datasetId: String): List<StoredNewsEntity>

    @Query("SELECT * FROM stored_news WHERE datasetId = :datasetId AND messageId = :messageId")
    suspend fun getByCompositeKey(datasetId: String, messageId: String): StoredNewsEntity?

    @Query("SELECT COUNT(*) FROM stored_news")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM stored_news WHERE datasetId = :datasetId")
    suspend fun getCountByDatasetId(datasetId: String): Int
}
