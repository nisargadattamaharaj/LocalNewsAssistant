package com.example.localnewsassistant

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsDatabaseFactory
import com.example.localnewsassistant.data.local.NewsSearchReader
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NewsSearchMigrationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var testDatabaseName: String
    private var database: NewsDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDatabaseName = "test_mig_1_2_" + UUID.randomUUID().toString().replace("-", "") + ".db"
    }

    @After
    fun tearDown() {
        // Guard: Ensure teardown only operates on test-owned UUID databases, never production news.db
        assertTrue(
            "Teardown must only operate on test-owned UUID databases",
            testDatabaseName.startsWith("test_mig_1_2_") && testDatabaseName != NewsDatabaseFactory.DEFAULT_DATABASE_NAME
        )

        // 1. Close active Room database connection without checking db.isOpen, do not swallow close exception
        database?.close()
        database = null

        // 2. Iterate over main file and sidecars, asserting file.delete() for any existing file
        val dbFile = NewsDatabaseFactory.getDatabaseFile(context, testDatabaseName)
        val filesToDelete = listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm"),
            File(dbFile.path + "-journal")
        )
        for (f in filesToDelete) {
            if (f.exists()) {
                val deleted = f.delete()
                assertTrue("Failed to delete test-owned file: ${f.path}", deleted)
            }
        }
    }

    @Test
    fun testRealFileDatabaseMigrationFromVersion1To2() = runBlocking {
        val dbFile = NewsDatabaseFactory.getDatabaseFile(context, testDatabaseName)

        // 1. Create legacy database version 1 using raw SupportSQLiteOpenHelper with schema 1 DDL
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `stored_news` (
                            `datasetId` TEXT NOT NULL,
                            `messageId` TEXT NOT NULL,
                            `source` TEXT NOT NULL,
                            `publishedAt` TEXT NOT NULL,
                            `text` TEXT NOT NULL,
                            `synthetic` INTEGER NOT NULL,
                            PRIMARY KEY(`datasetId`, `messageId`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val legacyOpenHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val legacyDb = legacyOpenHelper.writableDatabase

            // Pre-populate legacy rows
            val legacyRecords = listOf(
                listOf("dataset_1", "msg_001", "Source_1", "2026-10-21T10:00:00Z", "ЁЛКА свежая лесная", 1),
                listOf("dataset_1", "msg_002", "Source_2", "2026-10-21T11:00:00Z", "ёлка искусственная 100%", 1),
                listOf("dataset_1", "msg_003", "Source_3", "2026-10-21T12:00:00Z", "ЕЛКА зеленая", 1),
                listOf("dataset_2", "msg_001", "Source_4", "2026-10-21T13:00:00Z", "NEWS and НОВОСТИ", 1),
                listOf("dataset_2", "msg_002", "Source_5", "2026-10-21T14:00:00Z", "Е\u0308Ж и медведь в лесу", 1)
            )

            for (rec in legacyRecords) {
                legacyDb.execSQL(
                    "INSERT INTO stored_news (datasetId, messageId, source, publishedAt, text, synthetic) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(rec[0], rec[1], rec[2], rec[3], rec[4], rec[5])
                )
            }

            // Verify version 1 and pre-migration row count before closing
            var v1Count = 0
            legacyDb.query("SELECT COUNT(*) FROM stored_news").use { cursor ->
                if (cursor.moveToFirst()) {
                    v1Count = cursor.getInt(0)
                }
            }
            assertEquals(5, v1Count)
            assertEquals(1, legacyDb.version)
        } finally {
            legacyOpenHelper.close()
        }

        // 2. Open THE EXACT SAME FILE using NewsDatabaseFactory (runs MIGRATION_1_2)
        val realDb = NewsDatabaseFactory.createDatabase(context, testDatabaseName)
        database = realDb

        val dao = realDb.newsDao()

        // Trigger database opening and migration by executing a query
        val totalCount = dao.getTotalCount()
        assertEquals("Total row count must be preserved 100% after migration 1->2", 5, totalCount)

        // Verify version 2 via PRAGMA user_version
        val helper = realDb.openHelper.readableDatabase
        var v2Version = 0
        helper.query("PRAGMA user_version").use { cursor ->
            if (cursor.moveToFirst()) {
                v2Version = cursor.getInt(0)
            }
        }
        assertEquals("Database version must be updated to 2", 2, v2Version)

        // 3. Query ALL 5 migrated records directly by composite keys and assert all fields individually
        val rec1 = dao.getByCompositeKey("dataset_1", "msg_001")
        assertNotNull(rec1)
        assertEquals("dataset_1", rec1!!.datasetId)
        assertEquals("msg_001", rec1.messageId)
        assertEquals("Source_1", rec1.source)
        assertEquals("2026-10-21T10:00:00Z", rec1.publishedAt)
        assertEquals(true, rec1.synthetic)
        assertEquals("ЁЛКА свежая лесная", rec1.text)
        assertEquals("елка свежая лесная", rec1.normalizedText) // Hardcoded independent expected literal

        val rec2 = dao.getByCompositeKey("dataset_1", "msg_002")
        assertNotNull(rec2)
        assertEquals("dataset_1", rec2!!.datasetId)
        assertEquals("msg_002", rec2.messageId)
        assertEquals("Source_2", rec2.source)
        assertEquals("2026-10-21T11:00:00Z", rec2.publishedAt)
        assertEquals(true, rec2.synthetic)
        assertEquals("ёлка искусственная 100%", rec2.text)
        assertEquals("елка искусственная 100%", rec2.normalizedText) // Hardcoded independent expected literal

        val rec3 = dao.getByCompositeKey("dataset_1", "msg_003")
        assertNotNull(rec3)
        assertEquals("dataset_1", rec3!!.datasetId)
        assertEquals("msg_003", rec3.messageId)
        assertEquals("Source_3", rec3.source)
        assertEquals("2026-10-21T12:00:00Z", rec3.publishedAt)
        assertEquals(true, rec3.synthetic)
        assertEquals("ЕЛКА зеленая", rec3.text)
        assertEquals("елка зеленая", rec3.normalizedText) // Hardcoded independent expected literal

        val rec4 = dao.getByCompositeKey("dataset_2", "msg_001")
        assertNotNull(rec4)
        assertEquals("dataset_2", rec4!!.datasetId)
        assertEquals("msg_001", rec4.messageId)
        assertEquals("Source_4", rec4.source)
        assertEquals("2026-10-21T13:00:00Z", rec4.publishedAt)
        assertEquals(true, rec4.synthetic)
        assertEquals("NEWS and НОВОСТИ", rec4.text)
        assertEquals("news and новости", rec4.normalizedText) // Hardcoded independent expected literal

        val rec5 = dao.getByCompositeKey("dataset_2", "msg_002")
        assertNotNull(rec5)
        assertEquals("dataset_2", rec5!!.datasetId)
        assertEquals("msg_002", rec5.messageId)
        assertEquals("Source_5", rec5.source)
        assertEquals("2026-10-21T14:00:00Z", rec5.publishedAt)
        assertEquals(true, rec5.synthetic)
        assertEquals("Е\u0308Ж и медведь в лесу", rec5.text)
        assertEquals("еж и медведь в лесу", rec5.normalizedText) // Hardcoded independent expected literal

        // 4. Verify search functionality against migrated records
        val reader = NewsSearchReader(daoProvider = { dao })

        // Query "елка" matches "ЁЛКА", "ёлка", "ЕЛКА"
        val yoResults = reader.search("елка")
        assertEquals(3, yoResults.size)

        // Query "news" matches "NEWS and НОВОСТИ"
        val newsResults = reader.search("news")
        assertEquals(1, newsResults.size)

        // Query "еж" matches "ЁЛКА свежая лесная" (contains "еж" in "свежая") and "Е\u0308Ж и медведь в лесу"
        val ezResults = reader.search("еж")
        assertEquals(2, ezResults.size)
        assertEquals(
            listOf("dataset_1" to "msg_001", "dataset_2" to "msg_002"),
            ezResults.map { it.datasetId to it.messageId }
        )
        assertEquals(
            listOf("ЁЛКА свежая лесная", "Е\u0308Ж и медведь в лесу"),
            ezResults.map { it.text }
        )

        // Query "еж и медведь" matches specifically "Е\u0308Ж и медведь в лесу"
        val ezBearResults = reader.search("еж и медведь")
        assertEquals(1, ezBearResults.size)
        assertEquals("dataset_2", ezBearResults[0].datasetId)
        assertEquals("msg_002", ezBearResults[0].messageId)
        assertEquals("Е\u0308Ж и медведь в лесу", ezBearResults[0].text)
    }

    @Test
    fun testNormalizedSearch_EndToEndCaseAndYoInsensitivity() = runBlocking {
        val realDb = NewsDatabaseFactory.createDatabase(context, testDatabaseName)
        database = realDb

        val dao = realDb.newsDao()
        val entities = listOf(
            StoredNewsEntity(
                datasetId = "dataset_1",
                messageId = "msg_001",
                source = "Source_1",
                publishedAt = "2026-10-21T10:00:00Z",
                text = "ЁЛКА свежая лесная",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_1",
                messageId = "msg_002",
                source = "Source_2",
                publishedAt = "2026-10-21T11:00:00Z",
                text = "елка искусственная 100%",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_2",
                messageId = "msg_001",
                source = "Source_3",
                publishedAt = "2026-10-21T12:00:00Z",
                text = "Е\u0308Ж и медведь в лесу",
                synthetic = true
            )
        )

        dao.upsertAll(entities)

        val reader = NewsSearchReader(daoProvider = { dao })

        val eResults = reader.search("елка")
        assertEquals(2, eResults.size)
        assertEquals("ЁЛКА свежая лесная", eResults[0].text)

        val yoResults = reader.search("ЁЛКА")
        assertEquals(2, yoResults.size)

        // Query "ЕЖ" matches "ЁЛКА свежая лесная" (contains "еж" in "свежая") and "Е\u0308Ж и медведь в лесу"
        val ezResults = reader.search("ЕЖ")
        assertEquals(2, ezResults.size)
        assertEquals(
            listOf("dataset_1" to "msg_001", "dataset_2" to "msg_001"),
            ezResults.map { it.datasetId to it.messageId }
        )
        assertEquals(
            listOf("ЁЛКА свежая лесная", "Е\u0308Ж и медведь в лесу"),
            ezResults.map { it.text }
        )

        // Query "ЕЖ и медведь" matches specifically "Е\u0308Ж и медведь в лесу"
        val ezBearResults = reader.search("ЕЖ и медведь")
        assertEquals(1, ezBearResults.size)
        assertEquals("dataset_2", ezBearResults[0].datasetId)
        assertEquals("msg_001", ezBearResults[0].messageId)
        assertEquals("Е\u0308Ж и медведь в лесу", ezBearResults[0].text)
    }

    @Test
    fun testNormalizedSearch_UpsertRecalculatesNormalizedTextOnTextUpdate() = runBlocking {
        val realDb = NewsDatabaseFactory.createDatabase(context, testDatabaseName)
        database = realDb

        val dao = realDb.newsDao()
        val initialEntity = StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_100",
            source = "Source",
            publishedAt = "2026-10-21T10:00:00Z",
            text = "Первоначальная позиция",
            synthetic = true
        )
        dao.upsertAll(listOf(initialEntity))

        val reader = NewsSearchReader(daoProvider = { dao })

        val initialResults = reader.search("позиция")
        assertEquals(1, initialResults.size)

        val updatedEntity = initialEntity.copy(text = "Ёлка лесная")
        dao.upsertAll(listOf(updatedEntity))

        val oldQueryResults = reader.search("позиция")
        assertEquals(0, oldQueryResults.size)

        val newQueryResults = reader.search("елка")
        assertEquals(1, newQueryResults.size)
        assertEquals("Ёлка лесная", newQueryResults[0].text)
    }
}
