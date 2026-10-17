package com.example.localnewsassistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.SyntheticNewsStore
import com.example.localnewsassistant.model.SyntheticNewsAssetLoader
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsStorageInstrumentedTest {

    private lateinit var database: NewsDatabase
    private lateinit var newsStore: SyntheticNewsStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NewsDatabase::class.java).build()
        newsStore = SyntheticNewsStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testScenarioA_NewDatabaseIsEmpty() {
        runBlocking {
            assertEquals(0, database.newsDao().getTotalCount())
        }
    }

    @Test
    fun testScenarioB_RealAssetSavedAndExactFieldsMatched() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(context, "test_news.json")

            newsStore.storeDataset(dataset)

            val count = database.newsDao().getTotalCount()
            assertEquals(dataset.messages.size, count)

            val storedList = database.newsDao().getByDatasetId(dataset.datasetId)
            assertEquals(dataset.messages.size, storedList.size)

            for (i in dataset.messages.indices) {
                val originalMsg = dataset.messages[i]
                val storedEntity = storedList[i]

                assertEquals(dataset.datasetId, storedEntity.datasetId)
                assertEquals(originalMsg.id, storedEntity.messageId)
                assertEquals(originalMsg.source, storedEntity.source)
                assertEquals(originalMsg.publishedAt, storedEntity.publishedAt)
                assertEquals(originalMsg.text, storedEntity.text)
                assertTrue(storedEntity.synthetic)
            }
        }
    }

    @Test
    fun testScenarioC_DuplicateSaveSameDataset_NoDuplicateRowsCreated() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(context, "test_news.json")

            newsStore.storeDataset(dataset)
            val initialCount = database.newsDao().getTotalCount()

            newsStore.storeDataset(dataset)
            val countAfterSecondSave = database.newsDao().getTotalCount()

            assertEquals(initialCount, countAfterSecondSave)
        }
    }

    @Test
    fun testScenarioD_SameMessageIdDifferentDatasetId_IndependentRecords() {
        runBlocking {
            val dataset1 = SyntheticNewsDataset(
                datasetId = "dataset-alpha",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Источник Alpha", "2026-10-12T09:00:00+03:00", "Текст Alpha 1")
                )
            )

            val dataset2 = SyntheticNewsDataset(
                datasetId = "dataset-beta",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Источник Beta", "2026-10-12T10:00:00+03:00", "Текст Beta 1")
                )
            )

            newsStore.storeDataset(dataset1)
            newsStore.storeDataset(dataset2)

            assertEquals(2, database.newsDao().getTotalCount())

            val entity1 = database.newsDao().getByCompositeKey("dataset-alpha", "N001")
            assertNotNull(entity1)
            assertEquals("Источник Alpha", entity1!!.source)

            val entity2 = database.newsDao().getByCompositeKey("dataset-beta", "N001")
            assertNotNull(entity2)
            assertEquals("Источник Beta", entity2!!.source)
        }
    }

    @Test
    fun testScenarioE_RepeatSameKeyWithUpdatedFields_UpdatesInPlace() {
        runBlocking {
            val initialDataset = SyntheticNewsDataset(
                datasetId = "dataset-v1",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Старый источник", "2026-10-12T09:00:00+03:00", "Старый текст 1"),
                    SyntheticNewsMessage("N002", "Источник 2", "2026-10-12T10:00:00+03:00", "Текст 2")
                )
            )

            newsStore.storeDataset(initialDataset)

            val updatedDataset = SyntheticNewsDataset(
                datasetId = "dataset-v1",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Обновлённый источник", "2026-10-12T11:00:00+03:00", "Обновлённый текст 1")
                )
            )

            newsStore.storeDataset(updatedDataset)

            assertEquals(2, database.newsDao().getTotalCount())

            val updatedEntity = database.newsDao().getByCompositeKey("dataset-v1", "N001")
            assertNotNull(updatedEntity)
            assertEquals("Обновлённый источник", updatedEntity!!.source)
            assertEquals("2026-10-12T11:00:00+03:00", updatedEntity.publishedAt)
            assertEquals("Обновлённый текст 1", updatedEntity.text)

            val unchangedEntity = database.newsDao().getByCompositeKey("dataset-v1", "N002")
            assertNotNull(unchangedEntity)
            assertEquals("Текст 2", unchangedEntity!!.text)
        }
    }

    @Test
    fun testScenarioF_SyntheticFalseRejected_DatabaseUnchanged() {
        runBlocking {
            val nonSyntheticDataset = SyntheticNewsDataset(
                datasetId = "dataset-invalid",
                synthetic = false,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Источник", "2026-10-12T09:00:00+03:00", "Текст")
                )
            )

            try {
                newsStore.storeDataset(nonSyntheticDataset)
                fail("Should fail on synthetic = false")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("synthetic"))
            }

            assertEquals(0, database.newsDao().getTotalCount())
        }
    }

    @Test
    fun testScenarioG_InvalidMessageInBatch_RejectsEntireDatasetAtomicTransaction() {
        runBlocking {
            val initialDataset = SyntheticNewsDataset(
                datasetId = "dataset-existing",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Существующий источник", "2026-10-12T08:00:00+03:00", "Существующий текст")
                )
            )
            newsStore.storeDataset(initialDataset)
            assertEquals(1, database.newsDao().getTotalCount())

            val invalidDataset = SyntheticNewsDataset(
                datasetId = "dataset-new-invalid",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Валидный источник", "2026-10-12T09:00:00+03:00", "Валидный текст"),
                    SyntheticNewsMessage("N002", "Источник с плохой датой", "INVALID_DATE_FORMAT", "Текст 2")
                )
            )

            try {
                newsStore.storeDataset(invalidDataset)
                fail("Should fail validation due to invalid date")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("некорректную дату/время"))
            }

            assertEquals(1, database.newsDao().getTotalCount())
            assertNull(database.newsDao().getByCompositeKey("dataset-new-invalid", "N001"))
        }
    }

    @Test
    fun testScenarioH_ImportSubsetOfMessages_DoesNotDeleteOtherMessagesInDataset() {
        runBlocking {
            val fullDataset = SyntheticNewsDataset(
                datasetId = "dataset-full",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Источник 1", "2026-10-12T09:00:00+03:00", "Текст 1"),
                    SyntheticNewsMessage("N002", "Источник 2", "2026-10-12T10:00:00+03:00", "Текст 2")
                )
            )
            newsStore.storeDataset(fullDataset)
            assertEquals(2, database.newsDao().getTotalCount())

            val subsetDataset = SyntheticNewsDataset(
                datasetId = "dataset-full",
                synthetic = true,
                messages = listOf(
                    SyntheticNewsMessage("N001", "Источник 1 обновленный", "2026-10-12T09:30:00+03:00", "Текст 1 обновленный")
                )
            )
            newsStore.storeDataset(subsetDataset)

            assertEquals(2, database.newsDao().getTotalCount())
            assertNotNull(database.newsDao().getByCompositeKey("dataset-full", "N001"))
            assertNotNull(database.newsDao().getByCompositeKey("dataset-full", "N002"))
        }
    }
}
