package com.example.localnewsassistant.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "stored_news",
    primaryKeys = ["datasetId", "messageId"]
)
data class StoredNewsEntity(
    val datasetId: String,
    val messageId: String,
    val source: String,
    val publishedAt: String,
    val text: String,
    @ColumnInfo(defaultValue = "''")
    val normalizedText: String = "",
    val synthetic: Boolean
)
