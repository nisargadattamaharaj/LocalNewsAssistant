package com.example.localnewsassistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsSearchReader
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NewsSearchInstrumentedTest {

    private lateinit var database: NewsDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            NewsDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun testSearchByText_SubstringMatchingAndSanitization() = runBlocking {
        val dao = database.newsDao()
        val entities = listOf(
            StoredNewsEntity(
                datasetId = "dataset_A",
                messageId = "msg_001",
                source = "Channel_1",
                publishedAt = "2026-10-20T10:00:00Z",
                text = "CHANNEL_1_NEWS: Срочные новости: 100% скидка на товары!",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_A",
                messageId = "msg_002",
                source = "Channel_2",
                publishedAt = "2026-10-20T11:00:00Z",
                text = "CHANNEL_2_NEWS: Погода на завтра: солнечно и без осадков.",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_B",
                messageId = "msg_001",
                source = "Channel_3",
                publishedAt = "2026-10-20T12:00:00Z",
                text = "CHANNEL_3_NEWS: Аналитический обзор рынка: 100% рост акций_компании.",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_B",
                messageId = "msg_002",
                source = "Channel_4",
                publishedAt = "2026-10-20T13:00:00Z",
                text = "Отрицательный элемент 1000 без знака процента и акцийкомпании без подчёркивания",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_B",
                messageId = "msg_003",
                source = "Channel_5",
                publishedAt = "2026-10-20T14:00:00Z",
                text = "Ловушка акцийXкомпании с одиночным символом X вместо подчёркивания",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_C",
                messageId = "msg_001",
                source = "Channel_6",
                publishedAt = "2026-10-20T15:00:00Z",
                text = "Спецсимволы: категория\\важно и кавычки 'сегодня' \"срочно\"",
                synthetic = true
            )
        )
        dao.upsertAll(entities)

        val reader = NewsSearchReader(daoProvider = { dao })

        // Substring search "новости"
        val newsResults = reader.search("новости")
        assertEquals(1, newsResults.size)
        assertEquals("msg_001", newsResults[0].messageId)

        // Search with percent sign "100%" (escaped wildcard \%)
        val percentResults = reader.search("100%")
        assertEquals(2, percentResults.size)

        // Search with literal underscore "акций_компании" (escaped wildcard \_)
        val underscoreResults = reader.search("акций_компании")
        assertEquals(1, underscoreResults.size)
        assertEquals("dataset_B", underscoreResults[0].datasetId)
        assertEquals("msg_001", underscoreResults[0].messageId)

        // Search with literal backslash "\\"
        val backslashResults = reader.search("категория\\важно")
        assertEquals(1, backslashResults.size)
        assertEquals("dataset_C", backslashResults[0].datasetId)

        // Search with single quote
        val quoteResults = reader.search("'сегодня'")
        assertEquals(1, quoteResults.size)
        assertEquals("dataset_C", quoteResults[0].datasetId)

        // ASCII case-insensitivity search "channel" vs "CHANNEL"
        val asciiResultsLower = reader.search("channel")
        val asciiResultsUpper = reader.search("CHANNEL")
        assertEquals(3, asciiResultsLower.size)
        assertEquals(3, asciiResultsUpper.size)
    }

    @Test
    fun testSearchByText_NormalizedCyrillicCaseAndYoInsensitivity() = runBlocking {
        val dao = database.newsDao()
        val entities = listOf(
            StoredNewsEntity(
                datasetId = "dataset_1",
                messageId = "msg_001",
                source = "Source",
                publishedAt = "2026-10-20T10:00:00Z",
                text = "ЁЛКА зеленая пышная",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_1",
                messageId = "msg_002",
                source = "Source",
                publishedAt = "2026-10-20T11:00:00Z",
                text = "елка лесная зеленая",
                synthetic = true
            )
        )
        dao.upsertAll(entities)

        val reader = NewsSearchReader(daoProvider = { dao })

        // Searching "елка" or "ЁЛКА" matches BOTH entities in normalized search
        val eResults = reader.search("елка")
        assertEquals(2, eResults.size)
        assertEquals("ЁЛКА зеленая пышная", eResults[0].text) // Original text preserved 100%

        val yoResults = reader.search("ЁЛКА")
        assertEquals(2, yoResults.size)

        val cyrillicUpper = reader.search("ЗЕЛЕНАЯ")
        assertEquals(2, cyrillicUpper.size)
    }

    @Test
    fun testSearchByText_LimitFiftyAndOrdering() = runBlocking {
        val dao = database.newsDao()

        // Insert 60 entities in reversed/scrambled order
        val entities = (60 downTo 1).map { index ->
            StoredNewsEntity(
                datasetId = if (index <= 30) "dataset_1" else "dataset_2",
                messageId = String.format(Locale.US, "msg_%03d", index),
                source = "Source",
                publishedAt = "2026-10-20T10:00:00Z",
                text = "Тестовая запись номер $index для проверки лимита",
                synthetic = true
            )
        }
        dao.upsertAll(entities)

        val reader = NewsSearchReader(daoProvider = { dao })

        val results = reader.search("запись")
        assertEquals("Search query must be hard-limited to 50 results in SQL", 50, results.size)

        // Verify all 50 returned keys match dataset_1/msg_001..030 and dataset_2/msg_031..050 in exact sorted order
        for (i in 0 until 30) {
            assertEquals("dataset_1", results[i].datasetId)
            assertEquals(String.format(Locale.US, "msg_%03d", i + 1), results[i].messageId)
        }
        for (i in 30 until 50) {
            assertEquals("dataset_2", results[i].datasetId)
            assertEquals(String.format(Locale.US, "msg_%03d", i + 1), results[i].messageId)
        }
    }

    @Test
    fun testSearchByText_SameMessageIdAcrossDifferentDatasetIds() = runBlocking {
        val dao = database.newsDao()
        val entities = listOf(
            StoredNewsEntity(
                datasetId = "dataset_alpha",
                messageId = "msg_100",
                source = "Source_A",
                publishedAt = "2026-10-20T10:00:00Z",
                text = "Совпадение в датасете Alpha",
                synthetic = true
            ),
            StoredNewsEntity(
                datasetId = "dataset_beta",
                messageId = "msg_100",
                source = "Source_B",
                publishedAt = "2026-10-20T10:00:00Z",
                text = "Совпадение в датасете Beta",
                synthetic = true
            )
        )
        dao.upsertAll(entities)

        val reader = NewsSearchReader(daoProvider = { dao })
        val results = reader.search("Совпадение")

        assertEquals("Same messageId in two datasetIds must both be returned", 2, results.size)
        assertEquals("dataset_alpha", results[0].datasetId)
        assertEquals("dataset_beta", results[1].datasetId)
    }
}
