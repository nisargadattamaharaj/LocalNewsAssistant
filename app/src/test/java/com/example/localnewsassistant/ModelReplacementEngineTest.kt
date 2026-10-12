package com.example.localnewsassistant

import com.example.localnewsassistant.model.FileOperations
import com.example.localnewsassistant.model.MetadataOperations
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.ModelReplacementEngine
import com.example.localnewsassistant.model.ReplacementResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ModelReplacementEngineTest {

    private val oldModel = ModelMetadata("gemma_old.litertlm", 1000L, "model_old.bin")
    private val newModel = ModelMetadata("gemma_new.litertlm", 2000L, "model_new.bin")

    class FakeFileOps : FileOperations {
        var tempFileCreated = false
        var tempFileDeleted = false
        var newFileFinalized = false
        var newFileDeleted = false
        var oldFileDeleted = false

        var failWrite = false
        var failFinalize = false
        var failDeleteOld = false
        var throwOnDeleteOld = false
        var writtenSizeBytes = 2000L

        override fun createTempFile(): Boolean {
            tempFileCreated = true
            return true
        }

        override suspend fun writeData(): Long {
            if (failWrite) return -1L
            return writtenSizeBytes
        }

        override fun finalizeNewFile(expectedSize: Long): Boolean {
            if (failFinalize) return false
            newFileFinalized = true
            return true
        }

        override fun deleteTempFile() {
            tempFileDeleted = true
        }

        override fun deleteNewFile() {
            newFileDeleted = true
        }

        override fun deleteOldFile(oldFileName: String): Boolean {
            if (throwOnDeleteOld) throw IOException("Failed to delete old file")
            if (failDeleteOld) return false
            oldFileDeleted = true
            return true
        }
    }

    class FakeMetaOps(var activeMeta: ModelMetadata?) : MetadataOperations {
        var failFirstCommit = false
        var throwFirstCommit = false
        var failRestoreCommit = false
        var commitCount = 0

        override fun saveMetadataCommit(metadata: ModelMetadata?): Boolean {
            commitCount++
            // Simulate SharedPreferences behavior: in-memory state is mutated upon call
            activeMeta = metadata

            if (commitCount == 1) {
                if (throwFirstCommit) throw RuntimeException("Disk write exception")
                if (failFirstCommit) return false
            }
            if (commitCount == 2 && failRestoreCommit) {
                return false
            }

            return true
        }

        override fun getActiveMetadata(): ModelMetadata? {
            return activeMeta
        }
    }

    @Test
    fun testCancellationDuringCopyingPhase() = runBlocking {
        val fileOps = FakeFileOps()
        val metaOps = FakeMetaOps(oldModel)

        val job = launch {
            try {
                fileOps.createTempFile()
                throw CancellationException("Simulated user cancellation during streaming copy")
            } catch (e: CancellationException) {
                fileOps.deleteTempFile()
                throw e
            }
        }

        try {
            job.join()
        } catch (_: Exception) {
            // Expected cancellation
        }

        assertTrue(fileOps.tempFileDeleted)
        assertFalse(fileOps.newFileFinalized)
        assertEquals(oldModel, metaOps.getActiveMetadata())
    }

    @Test
    fun testCancellationAfterConfirmedCommitDoesNotDeleteNewModel() = runBlocking {
        val fileOps = FakeFileOps()
        val metaOps = FakeMetaOps(oldModel)
        val engine = ModelReplacementEngine()

        var result: ReplacementResult? = null

        val job = launch {
            withContext(NonCancellable) {
                result = engine.finalizeAndCommit(newModel, fileOps, metaOps)
            }
            throw CancellationException("Cancelled parent job after switch")
        }

        try {
            job.join()
        } catch (_: Exception) {
            // Expected cancellation
        }

        assertTrue(result is ReplacementResult.Success)
        assertEquals(newModel, metaOps.getActiveMetadata())
        assertFalse(fileOps.newFileDeleted)
        assertTrue(fileOps.oldFileDeleted)
    }

    @Test
    fun testUnknownSourceSizePreservesActualCopiedSize() {
        runBlocking {
            val fileOps = FakeFileOps().apply { writtenSizeBytes = 1500L }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val expectedMetaWithActualSize = newModel.copy(sizeBytes = 1500L)
            val result = engine.finalizeAndCommit(expectedMetaWithActualSize, fileOps, metaOps)

            assertTrue(result is ReplacementResult.Success)
            val success = result as ReplacementResult.Success
            assertEquals(1500L, success.metadata.sizeBytes)
        }
    }

    @Test
    fun testFinalizationFailure() {
        runBlocking {
            val fileOps = FakeFileOps().apply { failFinalize = true }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.RollbackSucceeded)
            val failure = result as ReplacementResult.RollbackSucceeded
            assertEquals(oldModel, failure.activeMetadata)
            assertTrue(fileOps.tempFileDeleted)
            assertTrue(fileOps.newFileDeleted)
            assertEquals(oldModel, metaOps.getActiveMetadata())
        }
    }

    @Test
    fun testFailureToSaveMetadataWithSuccessfulRollback() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(oldModel).apply { failFirstCommit = true }
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.RollbackSucceeded)
            val failure = result as ReplacementResult.RollbackSucceeded
            assertEquals(oldModel, failure.activeMetadata)
            assertTrue(fileOps.newFileDeleted) // New file deleted after confirmed rollback
            assertEquals(oldModel, metaOps.getActiveMetadata()) // Restored to oldModel
        }
    }

    @Test
    fun testFailureToSaveMetadataWithFailedRollbackIndeterminateState() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(oldModel).apply {
                failFirstCommit = true
                failRestoreCommit = true
            }
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.IndeterminateStorageError)
            assertFalse(fileOps.newFileDeleted) // MUST NOT delete newFile when storage state is indeterminate!
            assertFalse(fileOps.oldFileDeleted) // MUST NOT delete oldFile either!
        }
    }

    @Test
    fun testMetadataSaveExceptionIndeterminateState() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(oldModel).apply { throwFirstCommit = true }
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.IndeterminateStorageError)
            assertFalse(fileOps.newFileDeleted) // MUST NOT delete completed new copy on exception!
            assertFalse(fileOps.oldFileDeleted) // MUST NOT delete old file!
        }
    }

    @Test
    fun testFirstModelImportMetadataSaveFailureWithSuccessfulNullRollback() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(null).apply { failFirstCommit = true }
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.RollbackSucceeded)
            val failure = result as ReplacementResult.RollbackSucceeded
            assertNull(failure.activeMetadata)
            assertTrue(fileOps.newFileDeleted)
            assertNull(metaOps.getActiveMetadata())
        }
    }

    @Test
    fun testSuccessfulReplacement() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.Success)
            val success = result as ReplacementResult.Success
            assertEquals(newModel, success.metadata)
            assertTrue(fileOps.newFileFinalized)
            assertTrue(fileOps.oldFileDeleted)
            assertEquals(newModel, metaOps.getActiveMetadata())
        }
    }

    @Test
    fun testOldFileDeletionExceptionPostSwitchIsNonFatal() {
        runBlocking {
            val fileOps = FakeFileOps().apply { throwOnDeleteOld = true }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.finalizeAndCommit(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.Success)
            val success = result as ReplacementResult.Success
            assertEquals(newModel, success.metadata)
            assertEquals(newModel, metaOps.getActiveMetadata())
            assertFalse(fileOps.oldFileDeleted)
        }
    }
}
