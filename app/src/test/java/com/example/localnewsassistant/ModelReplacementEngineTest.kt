package com.example.localnewsassistant

import com.example.localnewsassistant.model.FileOperations
import com.example.localnewsassistant.model.MetadataOperations
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.ModelReplacementEngine
import com.example.localnewsassistant.model.ReplacementResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        override fun createTempFile(): Boolean {
            tempFileCreated = true
            return true
        }

        override suspend fun writeData(): Boolean {
            return !failWrite
        }

        override fun finalizeNewFile(): Boolean {
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
            if (failDeleteOld) return false
            oldFileDeleted = true
            return true
        }
    }

    class FakeMetaOps(var activeMeta: ModelMetadata?) : MetadataOperations {
        var failFirstCommit = false
        var failRestoreCommit = false
        var commitCount = 0

        override fun saveMetadataCommit(metadata: ModelMetadata?): Boolean {
            commitCount++
            // Simulate SharedPreferences behavior: in-memory state is mutated upon call
            activeMeta = metadata

            if (commitCount == 1 && failFirstCommit) {
                return false
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
    fun testFailureDuringCopyPhase() {
        runBlocking {
            val fileOps = FakeFileOps().apply { failWrite = true }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.RollbackSucceeded)
            val failure = result as ReplacementResult.RollbackSucceeded
            assertEquals(oldModel, failure.activeMetadata)
            assertTrue(fileOps.tempFileDeleted)
            assertFalse(fileOps.newFileDeleted) // newFile was never finalized
            assertEquals(oldModel, metaOps.getActiveMetadata())
        }
    }

    @Test
    fun testFailureToFinalizeNewFile() {
        runBlocking {
            val fileOps = FakeFileOps().apply { failFinalize = true }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

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

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

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

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.IndeterminateStorageError)
            assertFalse(fileOps.newFileDeleted) // MUST NOT delete newFile when storage state is indeterminate!
            assertFalse(fileOps.oldFileDeleted) // MUST NOT delete oldFile either!
        }
    }

    @Test
    fun testFirstModelImportMetadataSaveFailureWithSuccessfulNullRollback() {
        runBlocking {
            val fileOps = FakeFileOps()
            val metaOps = FakeMetaOps(null).apply { failFirstCommit = true }
            val engine = ModelReplacementEngine()

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

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

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.Success)
            val success = result as ReplacementResult.Success
            assertEquals(newModel, success.metadata)
            assertTrue(fileOps.newFileFinalized)
            assertTrue(fileOps.oldFileDeleted)
            assertEquals(newModel, metaOps.getActiveMetadata())
        }
    }

    @Test
    fun testFailureToDeleteOldFilePostReplacementIsNonFatal() {
        runBlocking {
            val fileOps = FakeFileOps().apply { failDeleteOld = true }
            val metaOps = FakeMetaOps(oldModel)
            val engine = ModelReplacementEngine()

            val result = engine.executeReplacement(newModel, fileOps, metaOps)

            assertTrue(result is ReplacementResult.Success)
            val success = result as ReplacementResult.Success
            assertEquals(newModel, success.metadata)
            assertEquals(newModel, metaOps.getActiveMetadata())
            assertFalse(fileOps.oldFileDeleted) // Non-fatal
        }
    }
}
