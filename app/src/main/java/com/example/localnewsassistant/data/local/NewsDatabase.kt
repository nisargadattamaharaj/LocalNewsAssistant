package com.example.localnewsassistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StoredNewsEntity::class],
    version = 2,
    exportSchema = true
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add column normalizedText TEXT NOT NULL DEFAULT ''
                db.execSQL("ALTER TABLE stored_news ADD COLUMN normalizedText TEXT NOT NULL DEFAULT ''")

                // 2. Batch cursor read and parameterized update with nested resource cleanup
                val stmt = db.compileStatement("UPDATE stored_news SET normalizedText = ? WHERE datasetId = ? AND messageId = ?")
                try {
                    val cursor = db.query("SELECT datasetId, messageId, text FROM stored_news")
                    try {
                        val datasetIdIdx = cursor.getColumnIndexOrThrow("datasetId")
                        val messageIdIdx = cursor.getColumnIndexOrThrow("messageId")
                        val textIdx = cursor.getColumnIndexOrThrow("text")

                        while (cursor.moveToNext()) {
                            val datasetId = cursor.getString(datasetIdIdx)
                            val messageId = cursor.getString(messageIdIdx)
                            val text = cursor.getString(textIdx)

                            val normalizedText = SearchTextNormalizer.normalize(text)

                            stmt.bindString(1, normalizedText)
                            stmt.bindString(2, datasetId)
                            stmt.bindString(3, messageId)
                            stmt.executeUpdateDelete()
                            stmt.clearBindings()
                        }
                    } finally {
                        cursor.close()
                    }
                } finally {
                    stmt.close()
                }
            }
        }
    }
}
