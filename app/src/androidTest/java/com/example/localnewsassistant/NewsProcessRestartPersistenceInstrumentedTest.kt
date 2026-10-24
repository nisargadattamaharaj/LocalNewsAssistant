package com.example.localnewsassistant

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsDatabaseFactory
import com.example.localnewsassistant.data.local.NewsSearchReader
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class NewsProcessRestartPersistenceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var runId: String
    private lateinit var dbName: String
    private lateinit var markerName: String
    private lateinit var rootDir: File

    private companion object {
        const val PREFIX = "test_pers_proc_"
        const val FIXTURE_ID = "restartfixture"
        const val TAG = "ProcessRestartTest"
        val RUN_ID_REGEX = Regex("^[0-9a-f]{32}$")
    }

    @Before
    fun setUp() {
        val supplied = InstrumentationRegistry.getArguments().getString("runId")
        require(supplied != null && RUN_ID_REGEX.matches(supplied)) {
            "Required runId must contain exactly 32 lowercase hex characters"
        }
        runId = supplied
        dbName = "$PREFIX$runId.db"
        markerName = "$PREFIX$runId.json"
        NewsDatabaseFactory.validateDatabaseName(dbName)
        require(dbName != NewsDatabaseFactory.DEFAULT_DATABASE_NAME)
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        rootDir = context.noBackupFilesDir.canonicalFile
    }

    private fun allowedNames(): List<String> = listOf(
        dbName, "$dbName-wal", "$dbName-shm", "$dbName-journal", markerName
    )

    private fun checkedFile(name: String): File {
        require(name in allowedNames()) { "File is not owned by this run" }
        val raw = File(rootDir, name)
        val canonical = raw.canonicalFile
        require(canonical.parentFile == rootDir && canonical.name == name) {
            "Canonical path or basename differs from the owned file"
        }
        return raw
    }

    private fun checkedFiles(): List<File> = allowedNames().map { checkedFile(it) }

    private fun expectedEntities(): List<StoredNewsEntity> = listOf(
        StoredNewsEntity(
            datasetId = "dataset_1", messageId = "msg_1", source = "Source_1",
            publishedAt = "2026-10-23T10:00:00Z", text = "RestartFixture ЁЛКА первая",
            normalizedText = "restartfixture елка первая", synthetic = true
        ),
        StoredNewsEntity(
            datasetId = "dataset_1", messageId = "msg_2", source = "Source_2",
            publishedAt = "2026-10-23T10:05:00Z", text = "RestartFixture елка ВТОРАЯ",
            normalizedText = "restartfixture елка вторая", synthetic = true
        ),
        StoredNewsEntity(
            datasetId = "dataset_2", messageId = "msg_1", source = "Source_3",
            publishedAt = "2026-10-23T10:10:00Z", text = "RestartFixture СОСНА",
            normalizedText = "restartfixture сосна", synthetic = true
        )
    )

    private suspend fun assertPersistedRows(database: NewsDatabase) {
        val dao = database.newsDao()
        val expected = expectedEntities()
        assertEquals("Exactly three rows are required", 3, dao.getTotalCount())
        for (entity in expected) {
            assertEquals(
                "All fields must match for ${entity.datasetId}/${entity.messageId}",
                entity,
                dao.getByCompositeKey(entity.datasetId, entity.messageId)
            )
        }
        val reader = NewsSearchReader(daoProvider = { dao })
        assertEquals("Full fixture and dataset/message ordering", expected, reader.search("restartfixture"))
        assertEquals("Both e/yo fixture rows and their ordering", expected.take(2), reader.search("елка"))
    }

    private fun addSecondary(primary: Throwable, secondary: Throwable) {
        if (primary !== secondary) primary.addSuppressed(secondary)
    }

    private fun retained(reason: String) {
        Log.w(TAG, "RETAINED runId=$runId reason=$reason files=${allowedNames().joinToString()}")
    }

    private fun closeSafely(database: NewsDatabase, primary: Throwable?): Boolean {
        try {
            database.close()
            return true
        } catch (closing: Throwable) {
            retained("close-failure")
            if (primary != null) {
                addSecondary(primary, closing)
                return false
            }
            throw closing
        }
    }

    private fun cleanupOwnedRun(ownershipConfirmed: Boolean) {
        check(ownershipConfirmed) { "Cleanup without ownership is forbidden" }
        val targets = checkedFiles()
        var failure: Throwable? = null
        for (file in targets) {
            try {
                check(!file.exists() || file.isFile) { "Refusing to delete a non-file owned path" }
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("Cannot delete owned test file ${file.name}")
                }
            } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else addSecondary(first, problem)
            }
        }
        val first = failure
        if (first != null) throw first
        for (file in checkedFiles()) {
            assertFalse("Owned file remains after cleanup: ${file.name}", file.exists())
        }
    }

    private fun cleanupPreservingFailure(ownershipConfirmed: Boolean, primary: Throwable?) {
        try {
            cleanupOwnedRun(ownershipConfirmed)
        } catch (cleanup: Throwable) {
            retained("cleanup-failure")
            if (primary != null) addSecondary(primary, cleanup) else throw cleanup
        }
    }

    private fun readReadyMarker(): Int {
        val marker = checkedFile(markerName)
        check(marker.isFile && marker.length() in 1L..4096L) { "Missing or invalid marker file" }
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        assertEquals("Marker version", 1, json.getInt("version"))
        assertEquals("Marker runId", runId, json.getString("runId"))
        assertEquals("Marker database", dbName, json.getString("dbName"))
        assertEquals("Marker fixture", FIXTURE_ID, json.getString("fixture"))
        assertEquals("Marker fixture version", 1, json.getInt("fixtureVersion"))
        assertEquals("Marker count", 3, json.getInt("count"))
        assertEquals("Marker stage", "READY", json.getString("stage"))
        return json.getInt("pidA").also { check(it > 0) { "Invalid writer PID" } }
    }

    private fun writeReadyMarker(pidA: Int) {
        check(pidA > 0)
        val marker = checkedFile(markerName)
        check(marker.isFile && marker.length() == 0L) { "Marker reservation was changed" }
        val json = JSONObject().apply {
            put("version", 1)
            put("runId", runId)
            put("dbName", dbName)
            put("pidA", pidA)
            put("fixture", FIXTURE_ID)
            put("fixtureVersion", 1)
            put("count", 3)
            put("stage", "READY")
        }
        FileOutputStream(marker, false).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        assertEquals("Marker readback must match writer PID", pidA, readReadyMarker())
    }

    @Test
    fun testPhase1_writeSyntheticDatasetAndStoreToken(): Unit = runBlocking<Unit> {
        val files = checkedFiles()
        for (file in files) {
            check(!file.exists()) { "Run already has files: ${file.name}; refusing overwrite" }
        }
        val marker = checkedFile(markerName)
        check(marker.createNewFile()) { "Cannot reserve marker; run is not owned" }
        var closeSucceeded = false
        try {
            val database = NewsDatabaseFactory.createDatabase(context, dbName)
            var primary: Throwable? = null
            try {
                database.withTransaction {
                    database.newsDao().upsertAll(expectedEntities().map { it.copy(normalizedText = "") })
                }
                assertPersistedRows(database)
            } catch (problem: Throwable) {
                primary = problem
                throw problem
            } finally {
                closeSucceeded = closeSafely(database, primary)
            }
            check(checkedFile(dbName).isFile) { "Database was not created" }
            val pidA = Process.myPid()
            writeReadyMarker(pidA)
            Log.i(TAG, "READY runId=$runId pidA=$pidA count=3 path=${checkedFile(dbName).canonicalPath}")
        } catch (problem: Throwable) {
            if (closeSucceeded) cleanupPreservingFailure(true, problem) else retained("phase1-not-ready")
            throw problem
        }
    }

    @Test
    fun testPhase2_readAndVerifyDatabaseFromNewProcess(): Unit = runBlocking<Unit> {
        checkedFiles()
        val pidA = readReadyMarker()
        val pidB = Process.myPid()
        assertTrue("Two distinct PIDs are required; equality is inconclusive for this scenario", pidA != pidB)
        check(checkedFile(dbName).isFile) { "Existing database is required before opening" }

        var database: NewsDatabase? = null
        var primary: Throwable? = null
        var closeSucceeded = false
        try {
            val opened = NewsDatabaseFactory.createDatabase(context, dbName)
            database = opened
            assertPersistedRows(opened)
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            val opened = database
            if (opened != null) closeSucceeded = closeSafely(opened, primary)
            if (closeSucceeded) cleanupPreservingFailure(true, primary) else retained("phase2-not-closed")
        }
        Log.i(TAG, "PHASE2_SUCCESS runId=$runId pidA=$pidA pidB=$pidB count=3 cleanup=complete")
    }
}
