package com.example.localnewsassistant

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsDatabaseFactory
import com.example.localnewsassistant.data.local.SyntheticNewsStore
import com.example.localnewsassistant.model.SyntheticNewsAssetLoader
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NewsFilePersistenceInstrumentedTest {

    companion object {
        private const val TEST_DB_PREFIX = "test_pers_"
    }

    private lateinit var context: Context
    private lateinit var testDbName: String
    private val activeDatabases = mutableMapOf<String, MutableList<NewsDatabase>>()
    private val createdDbNames = mutableSetOf<String>()

    var customCloseInterceptor: ((NewsDatabase, String) -> Unit)? = null
    val deletionAttempts = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDbName = createUniqueTestDbName()
    }

    @After
    fun tearDown() {
        customCloseInterceptor = null
        performAllTestCleanup()
    }

    private fun performAllTestCleanup() {
        val cleanupExceptions = mutableListOf<Exception>()

        for (dbName in createdDbNames.toList()) {
            try {
                cleanTestDatabaseInternal(dbName)
            } catch (e: Exception) {
                cleanupExceptions.add(e)
            }
        }

        if (cleanupExceptions.isNotEmpty()) {
            val first = cleanupExceptions.first()
            for (i in 1 until cleanupExceptions.size) {
                first.addSuppressed(cleanupExceptions[i])
            }
            throw first
        }
    }

    private fun createUniqueTestDbName(): String {
        val uniqueName = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "_") + ".db"
        NewsDatabaseFactory.validateDatabaseName(uniqueName)

        val dbFile = NewsDatabaseFactory.getDatabaseFile(context, uniqueName)
        val sidecars = listOf(
            dbFile,
            File("${dbFile.path}-wal"),
            File("${dbFile.path}-shm"),
            File("${dbFile.path}-journal")
        )

        for (file in sidecars) {
            if (file.exists()) {
                fail("Тестовый файл БД или sidecar уже существует до начала теста: ${file.absolutePath}")
            }
        }

        createdDbNames.add(uniqueName)
        return uniqueName
    }

    private fun createTrackedDatabase(dbName: String = testDbName): NewsDatabase {
        if (!createdDbNames.contains(dbName)) {
            fail("Попытка открыть незарегистрированное тестовое имя БД: '$dbName'")
        }
        val db = NewsDatabaseFactory.createDatabase(context, dbName)
        activeDatabases.getOrPut(dbName) { mutableListOf() }.add(db)
        return db
    }

    private fun closeDatabaseHandle(db: NewsDatabase, dbName: String) {
        customCloseInterceptor?.invoke(db, dbName)
        db.close()
        // Removal from activeDatabases occurs ONLY after db.close() completes without exception
        activeDatabases[dbName]?.remove(db)
        if (activeDatabases[dbName]?.isEmpty() == true) {
            activeDatabases.remove(dbName)
        }
    }

    private fun closeTrackedDatabase(db: NewsDatabase, dbName: String = testDbName) {
        closeDatabaseHandle(db, dbName)
    }

    private fun closeAllDatabaseHandlesFor(dbName: String) {
        val list = activeDatabases[dbName]?.toList() ?: return
        val closeExceptions = mutableListOf<Exception>()
        for (db in list) {
            try {
                closeDatabaseHandle(db, dbName)
            } catch (e: Exception) {
                closeExceptions.add(e)
            }
        }
        if (closeExceptions.isNotEmpty()) {
            val first = closeExceptions.first()
            for (i in 1 until closeExceptions.size) {
                first.addSuppressed(closeExceptions[i])
            }
            throw first
        }
    }

    private fun cleanTestDatabaseInternal(dbName: String) {
        // Guard 1: Owned check
        if (!createdDbNames.contains(dbName)) {
            throw IllegalArgumentException("Guard violation: Имя '$dbName' не зарегистрировано в реестре текущего теста")
        }

        // Guard 2: Prefix check
        if (!dbName.startsWith(TEST_DB_PREFIX)) {
            throw IllegalArgumentException("Guard violation: Имя '$dbName' не имеет тестового префикса '$TEST_DB_PREFIX'")
        }

        // Guard 3: Prohibit DEFAULT_DATABASE_NAME
        if (dbName == NewsDatabaseFactory.DEFAULT_DATABASE_NAME) {
            throw IllegalArgumentException("Guard violation: Запрещено удалять дефолтную базу данных '${NewsDatabaseFactory.DEFAULT_DATABASE_NAME}'")
        }

        // Guard 4: Validate name syntax
        NewsDatabaseFactory.validateDatabaseName(dbName)

        // Guard 5: Canonical parent and file check for database and sidecars
        val dbFile = NewsDatabaseFactory.getDatabaseFile(context, dbName)
        val noBackupDir = context.noBackupFilesDir
            ?: throw IllegalStateException("noBackupFilesDir недоступен")

        val canonicalDbFile = dbFile.canonicalFile
        val canonicalNoBackupDir = noBackupDir.canonicalFile

        if (canonicalDbFile.parentFile?.canonicalPath != canonicalNoBackupDir.canonicalPath) {
            throw IllegalArgumentException("Guard violation: Файл '${canonicalDbFile.absolutePath}' находится вне noBackupFilesDir")
        }

        if (canonicalDbFile.name != dbName) {
            throw IllegalArgumentException("Guard violation: Каноническое имя файла '${canonicalDbFile.name}' не совпадает с '$dbName'")
        }

        val sidecarFiles = listOf(
            canonicalDbFile,
            File("${canonicalDbFile.path}-wal").canonicalFile,
            File("${canonicalDbFile.path}-shm").canonicalFile,
            File("${canonicalDbFile.path}-journal").canonicalFile
        )

        for (file in sidecarFiles) {
            if (file.parentFile?.canonicalPath != canonicalNoBackupDir.canonicalPath) {
                throw IllegalArgumentException("Guard violation: Sidecar файл '${file.absolutePath}' находится вне noBackupFilesDir")
            }
            if (file.name !in setOf(dbName, "$dbName-wal", "$dbName-shm", "$dbName-journal")) {
                throw IllegalArgumentException("Guard violation: Sidecar имя '${file.name}' не относится к '$dbName'")
            }
        }

        // Close any remaining handles for this dbName FIRST
        closeAllDatabaseHandlesFor(dbName)

        // If unclosed handles remain, abort deletion!
        if (activeDatabases[dbName]?.isNotEmpty() == true) {
            throw IllegalStateException("Guard violation: Невозможно удалить БД '$dbName', пока остаются незакрытые handles")
        }

        // Increment deletion attempts counter right before file deletion
        deletionAttempts[dbName] = (deletionAttempts[dbName] ?: 0) + 1

        // Perform deletion
        context.deleteDatabase(canonicalDbFile.absolutePath)

        for (file in sidecarFiles) {
            if (file.exists()) {
                file.delete()
            }
        }

        // Verifiable cleanup check
        for (file in sidecarFiles) {
            if (file.exists()) {
                throw IllegalStateException("Cleanup error: Файл '${file.absolutePath}' остался после очистки")
            }
        }

        // Only after confirmed file deletion, remove dbName from createdDbNames
        createdDbNames.remove(dbName)
    }

    @Test
    fun testScenarioA_DatabaseFileExistsUnderNoBackupFilesDir() {
        runBlocking {
            withTimeout(5000L) {
                val db = createTrackedDatabase(testDbName)
                val count = db.newsDao().getTotalCount()
                assertEquals(0, count)

                val expectedFile = NewsDatabaseFactory.getDatabaseFile(context, testDbName)
                assertTrue(expectedFile.exists())

                val noBackupDir = context.noBackupFilesDir
                assertEquals(noBackupDir.canonicalPath, expectedFile.parentFile?.canonicalPath)

                val actualPath = db.openHelper.writableDatabase.path
                assertNotNull(actualPath)
                assertEquals(expectedFile.canonicalPath, File(actualPath!!).canonicalPath)
            }
        }
    }

    @Test
    fun testScenarioB_SaveAssetCloseAndReopenNewInstance_DataPreservedExactFields() {
        runBlocking {
            withTimeout(5000L) {
                val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(context, "test_news.json")

                // 1. Open db1 and store dataset
                val db1 = createTrackedDatabase(testDbName)
                val store1 = SyntheticNewsStore(db1)
                store1.storeDataset(dataset)

                val initialCount = db1.newsDao().getTotalCount()
                assertEquals(dataset.messages.size, initialCount)

                // Close db1 via tracked close
                closeTrackedDatabase(db1, testDbName)
                assertFalse(db1.isOpen)

                // 2. Open db2 new instance with same test database name
                val db2 = createTrackedDatabase(testDbName)
                assertNotSame(db1, db2)

                val countAfterReopen = db2.newsDao().getTotalCount()
                assertEquals(dataset.messages.size, countAfterReopen)

                val storedList = db2.newsDao().getByDatasetId(dataset.datasetId)
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
    }

    @Test
    fun testScenarioC_ReopenResaveAndInPlaceUpdate() {
        runBlocking {
            withTimeout(5000L) {
                val initialDataset = SyntheticNewsDataset(
                    datasetId = "dataset-v1",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N001", "Источник 1", "2026-10-12T09:00:00+03:00", "Текст 1"),
                        SyntheticNewsMessage("N002", "Источник 2", "2026-10-12T10:00:00+03:00", "Текст 2")
                    )
                )

                val db1 = createTrackedDatabase(testDbName)
                SyntheticNewsStore(db1).storeDataset(initialDataset)
                closeTrackedDatabase(db1, testDbName)

                val db2 = createTrackedDatabase(testDbName)
                val store2 = SyntheticNewsStore(db2)

                // Re-store identical dataset -> count unchanged
                store2.storeDataset(initialDataset)
                assertEquals(2, db2.newsDao().getTotalCount())

                // Re-store updated dataset
                val updatedDataset = SyntheticNewsDataset(
                    datasetId = "dataset-v1",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N001", "Источник 1 Обновленный", "2026-10-12T11:00:00+03:00", "Текст 1 Обновленный")
                    )
                )
                store2.storeDataset(updatedDataset)
                closeTrackedDatabase(db2, testDbName)

                val db3 = createTrackedDatabase(testDbName)
                assertEquals(2, db3.newsDao().getTotalCount())

                val updatedEntity = db3.newsDao().getByCompositeKey("dataset-v1", "N001")
                assertNotNull(updatedEntity)
                assertEquals("Источник 1 Обновленный", updatedEntity!!.source)
                assertEquals("2026-10-12T11:00:00+03:00", updatedEntity.publishedAt)
                assertEquals("Текст 1 Обновленный", updatedEntity.text)

                val unchangedEntity = db3.newsDao().getByCompositeKey("dataset-v1", "N002")
                assertNotNull(unchangedEntity)
                assertEquals("Текст 2", unchangedEntity!!.text)
            }
        }
    }

    @Test
    fun testScenarioD_TwoDatasetIdsWithSameMessageIdsPersistIndependently() {
        runBlocking {
            withTimeout(5000L) {
                val datasetA = SyntheticNewsDataset(
                    datasetId = "dataset-A",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N001", "Источник А", "2026-10-12T09:00:00+03:00", "Текст А")
                    )
                )

                val datasetB = SyntheticNewsDataset(
                    datasetId = "dataset-B",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N001", "Источник B", "2026-10-12T10:00:00+03:00", "Текст B")
                    )
                )

                val db1 = createTrackedDatabase(testDbName)
                val store1 = SyntheticNewsStore(db1)
                store1.storeDataset(datasetA)
                store1.storeDataset(datasetB)
                closeTrackedDatabase(db1, testDbName)

                val db2 = createTrackedDatabase(testDbName)
                assertEquals(2, db2.newsDao().getTotalCount())

                val entityA = db2.newsDao().getByCompositeKey("dataset-A", "N001")
                assertNotNull(entityA)
                assertEquals("Источник А", entityA!!.source)

                val entityB = db2.newsDao().getByCompositeKey("dataset-B", "N001")
                assertNotNull(entityB)
                assertEquals("Источник B", entityB!!.source)
            }
        }
    }

    @Test
    fun testScenarioE_TwoSeparateTestDatabasesDoNotMixDataAndIsolatedCleanup() {
        runBlocking {
            withTimeout(5000L) {
                val dbName1 = createUniqueTestDbName()
                val dbName2 = createUniqueTestDbName()

                val dataset1 = SyntheticNewsDataset(
                    datasetId = "ds-1",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N001", "Источник 1", "2026-10-12T09:00:00+03:00", "Текст 1")
                    )
                )

                val dataset2 = SyntheticNewsDataset(
                    datasetId = "ds-2",
                    synthetic = true,
                    messages = listOf(
                        SyntheticNewsMessage("N002", "Источник 2", "2026-10-12T10:00:00+03:00", "Текст 2")
                    )
                )

                val db1 = createTrackedDatabase(dbName1)
                SyntheticNewsStore(db1).storeDataset(dataset1)
                closeTrackedDatabase(db1, dbName1)

                val db2 = createTrackedDatabase(dbName2)
                SyntheticNewsStore(db2).storeDataset(dataset2)
                closeTrackedDatabase(db2, dbName2)

                // Delete dbName1 and verify its files are gone
                cleanTestDatabaseInternal(dbName1)
                val db1File = NewsDatabaseFactory.getDatabaseFile(context, dbName1)
                assertFalse(db1File.exists())

                // Open db2 again -> data in db2 must be intact
                val db2Reopened = createTrackedDatabase(dbName2)
                assertEquals(1, db2Reopened.newsDao().getTotalCount())

                val entity = db2Reopened.newsDao().getByCompositeKey("ds-2", "N002")
                assertNotNull(entity)
                assertEquals("Источник 2", entity!!.source)
            }
        }
    }

    @Test
    fun testScenarioF_UnsafeDatabaseNamesRejectedBeforeOpening() {
        val unsafeNames = listOf(
            "",
            "   ",
            "../bad.db",
            "/data/data/bad.db",
            "test:db.db",
            ":memory:",
            "invalid_ext.txt"
        )

        for (unsafeName in unsafeNames) {
            try {
                NewsDatabaseFactory.createDatabase(context, unsafeName)
                fail("Should fail on unsafe database name: '$unsafeName'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Недопустимое имя файла базы данных"))
            }
        }
    }

    @Test
    fun testScenarioG_CleanupRejectsDefaultAndUnownedNames() {
        val rejectedNames = listOf(
            NewsDatabaseFactory.DEFAULT_DATABASE_NAME,
            "test_pers_unowned_database.db",
            "../bad.db"
        )

        for (unownedName in rejectedNames) {
            try {
                cleanTestDatabaseInternal(unownedName)
                fail("Should reject cleanup for unowned or default name: '$unownedName'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Guard violation"))
            }
        }
    }

    @Test
    fun testScenarioH_CloseFailurePreservesHandlesAndProhibitsDeletion() {
        val regDbName = createUniqueTestDbName()
        val db = createTrackedDatabase(regDbName)

        val expectedCloseEx = IOException("Simulated close failure")

        try {
            runBlocking {
                withTimeout(5000L) {
                    val dataset = SyntheticNewsDataset(
                        datasetId = "ds-reg",
                        synthetic = true,
                        messages = listOf(
                            SyntheticNewsMessage("N001", "Источник Reg", "2026-10-12T09:00:00+03:00", "Текст Reg")
                        )
                    )
                    SyntheticNewsStore(db).storeDataset(dataset)
                    assertEquals(1, db.newsDao().getTotalCount())
                }
            }

            val dbFile = NewsDatabaseFactory.getDatabaseFile(context, regDbName)
            assertTrue(dbFile.exists())

            // Set close interceptor to simulate close failure
            customCloseInterceptor = { _, targetDbName ->
                if (targetDbName == regDbName) {
                    throw expectedCloseEx
                }
            }

            var caughtEx: Exception? = null
            try {
                performAllTestCleanup()
                fail("Should fail cleanup due to close exception")
            } catch (e: Exception) {
                caughtEx = e
            }

            assertNotNull(caughtEx)
            val actualCause = if (caughtEx === expectedCloseEx) caughtEx else caughtEx?.suppressed?.firstOrNull { it === expectedCloseEx } ?: caughtEx
            assertSame(expectedCloseEx, actualCause)

            // Verifications:
            // 1. Deletion attempts for regDbName == 0
            assertEquals(0, deletionAttempts[regDbName] ?: 0)

            // 2. Database file still exists on disk
            assertTrue(dbFile.exists())

            // 3. regDbName still registered in createdDbNames
            assertTrue(createdDbNames.contains(regDbName))

            // 4. Unclosed handle still tracked in activeDatabases
            assertTrue(activeDatabases[regDbName]?.contains(db) == true)
        } finally {
            // Always reset interceptor in finally block without empty catch
            customCloseInterceptor = null
            performAllTestCleanup()
        }
    }

    @Test
    fun testScenarioI_CountReaderReadsZeroThenStoredSyntheticDataset() = runBlocking {
        val isolatedDatabase = createTrackedDatabase()

        val reader = com.example.localnewsassistant.data.local.NewsCountReader(
            daoProvider = { isolatedDatabase.newsDao() },
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        )

        val initialCount = reader.getTotalCount()
        assertEquals("Newly created isolated database should have 0 messages", 0, initialCount)

        val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(context, "test_news.json")
        assertTrue("Fixture must contain at least one message", dataset.messages.isNotEmpty())

        SyntheticNewsStore(isolatedDatabase).storeDataset(dataset)

        val expectedCount = dataset.messages.size
        val countAfterStore = reader.getTotalCount()
        assertEquals("NewsCountReader should return the exact number of stored synthetic messages", expectedCount, countAfterStore)

        val countSecondRead = reader.getTotalCount()
        assertEquals("NewsCountReader should return the identical count on subsequent read", expectedCount, countSecondRead)
    }
}
