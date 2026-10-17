package com.example.localnewsassistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [StoredNewsEntity::class],
    version = 1,
    exportSchema = true
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
