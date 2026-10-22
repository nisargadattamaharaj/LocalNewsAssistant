package com.example.localnewsassistant.data.local

import android.content.Context
import androidx.room.Room
import java.io.File

object NewsDatabaseFactory {

    const val DEFAULT_DATABASE_NAME = "news.db"
    private val SAFE_BASENAME_REGEX = Regex("^[a-zA-Z0-9_-]+\\.db$")

    fun validateDatabaseName(databaseName: String) {
        if (databaseName.isBlank() || databaseName.contains("..") || !SAFE_BASENAME_REGEX.matches(databaseName)) {
            throw IllegalArgumentException("Недопустимое имя файла базы данных: '$databaseName'")
        }
    }

    fun getDatabaseFile(context: Context, databaseName: String = DEFAULT_DATABASE_NAME): File {
        validateDatabaseName(databaseName)
        val appContext = context.applicationContext
        val noBackupDir = appContext.noBackupFilesDir
            ?: throw IllegalStateException("noBackupFilesDir недоступен в текущем контексте")
        return File(noBackupDir, databaseName)
    }

    fun createDatabase(context: Context, databaseName: String = DEFAULT_DATABASE_NAME): NewsDatabase {
        val dbFile = getDatabaseFile(context, databaseName)
        return Room.databaseBuilder(
            context.applicationContext,
            NewsDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(NewsDatabase.MIGRATION_1_2)
            .build()
    }
}
