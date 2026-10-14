package com.example.localnewsassistant

import com.example.localnewsassistant.model.IndeterminateStorageException
import com.example.localnewsassistant.model.ModelLoadResult
import com.example.localnewsassistant.model.ModelMetadata
import com.example.localnewsassistant.model.OperationCoordinator
import com.example.localnewsassistant.model.OperationType
import com.example.localnewsassistant.ui.ModelImportFinalizer
import com.example.localnewsassistant.ui.ModelUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ModelImportCancellationTest {

    @Test
    fun testA_CancellationBeforeCommitRestoresPreviousModelAndCleansTemp() = runBlocking {
        var cleanCalled = false
        var cleanedActiveFileName: String? = null
        var finalUiState: ModelUiState? = null

        val previousMetadata = ModelMetadata("prev_model.litertlm", 1000L, "model_prev.litertlm")
        val originException = CancellationException("Import cancelled during copy")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.Valid(previousMetadata) },
                cleanTempFiles = { active ->
                    cleanCalled = true
                    cleanedActiveFileName = active
                },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertTrue(cleanCalled)
        assertEquals("model_prev.litertlm", cleanedActiveFileName)
        assertTrue(finalUiState is ModelUiState.Imported)
        val imported = finalUiState as ModelUiState.Imported
        assertEquals("prev_model.litertlm", imported.metadata.originalName)
    }

    @Test
    fun testB_CancellationWithNoPreviousModelSetsNoModelAndCleansTemp() = runBlocking {
        var cleanCalled = false
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Import cancelled with no model")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.NoModel },
                cleanTempFiles = { cleanCalled = true },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertTrue(cleanCalled)
        assertEquals(ModelUiState.NoModel, finalUiState)
    }

    @Test
    fun testC_CancellationAfterCommitReflectsNewCommittedModelAndKeepsNewActiveFile() = runBlocking {
        val newMetadata = ModelMetadata("new_model.litertlm", 2000L, "model_new.litertlm")
        var cleanedActiveFileName: String? = null
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Import cancelled on IO return")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.Valid(newMetadata) },
                cleanTempFiles = { active -> cleanedActiveFileName = active },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals("model_new.litertlm", cleanedActiveFileName)
        assertTrue(finalUiState is ModelUiState.Imported)
        val imported = finalUiState as ModelUiState.Imported
        assertEquals("new_model.litertlm", imported.metadata.originalName)
    }

    @Test
    fun testD_LoadMetadataIOExceptionInFinalizerSetsIndeterminateErrorAndSkipsCleanup() = runBlocking {
        var cleanCallCount = 0
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Cancelled during IO read error")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { throw IOException("Disk read error") },
                cleanTempFiles = { cleanCallCount++ },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals(0, cleanCallCount)
        assertTrue(finalUiState is ModelUiState.IndeterminateError)
        val errorState = finalUiState as ModelUiState.IndeterminateError
        assertTrue(errorState.message.contains("Disk read error"))
    }

    @Test
    fun testE_LoadMetadataIndeterminateExceptionInFinalizerSkipsCleanupAndSetsIndeterminateError() = runBlocking {
        var cleanCallCount = 0
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Cancelled during indeterminate state")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { throw IndeterminateStorageException("Storage unrecoverable") },
                cleanTempFiles = { cleanCallCount++ },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals(0, cleanCallCount)
        assertTrue(finalUiState is ModelUiState.IndeterminateError)
        val errorState = finalUiState as ModelUiState.IndeterminateError
        assertTrue(errorState.message.contains("Storage unrecoverable"))
    }

    @Test
    fun testF_LoadMetadataCorruptedInFinalizerSkipsCleanupAndSetsIndeterminateError() = runBlocking {
        var cleanCallCount = 0
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Cancelled during corrupted read")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.Corrupted("file.bin", 100L, "File corrupted") },
                cleanTempFiles = { cleanCallCount++ },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals(0, cleanCallCount)
        assertTrue(finalUiState is ModelUiState.IndeterminateError)
        val errorState = finalUiState as ModelUiState.IndeterminateError
        assertTrue(errorState.message.contains("File corrupted"))
    }

    @Test
    fun testG_CleanupFailureAfterValidAttachesSuppressedAndSetsErrorState() = runBlocking {
        var finalUiState: ModelUiState? = null
        val validMetadata = ModelMetadata("valid_model.litertlm", 1000L, "model_valid.litertlm")
        val originException = CancellationException("Import cancelled")
        val cleanupException = IOException("Temp file deletion failed")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.Valid(validMetadata) },
                cleanTempFiles = { throw cleanupException },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals(1, originException.suppressedExceptions.size)
        assertEquals(cleanupException, originException.suppressedExceptions[0])

        assertTrue(finalUiState is ModelUiState.Error)
        val errorState = finalUiState as ModelUiState.Error
        assertTrue(errorState.message.contains("ошибка очистки временных файлов"))
        assertEquals(validMetadata, errorState.previousMetadata)
    }

    @Test
    fun testH_CleanupFailureAfterNoModelAttachesSuppressedAndSetsErrorState() = runBlocking {
        var finalUiState: ModelUiState? = null
        val originException = CancellationException("Import cancelled")
        val cleanupException = IOException("Temp file deletion failed")

        var thrownException: Throwable? = null
        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { ModelLoadResult.NoModel },
                cleanTempFiles = { throw cleanupException },
                updateUiState = { newState -> finalUiState = newState }
            )
        } catch (e: Throwable) {
            thrownException = e
        }

        assertEquals(originException, thrownException)
        assertEquals(1, originException.suppressedExceptions.size)
        assertEquals(cleanupException, originException.suppressedExceptions[0])

        assertTrue(finalUiState is ModelUiState.Error)
        val errorState = finalUiState as ModelUiState.Error
        assertTrue(errorState.message.contains("ошибка очистки временных файлов"))
        assertNull(errorState.previousMetadata)
    }

    @Test
    fun testI_TrulyCancelledCoroutineExecutesFinalizerUnderNonCancellable() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val taskStarted = CompletableDeferred<Unit>()
        val cancelBarrier = CompletableDeferred<Unit>()
        var finalUiState: ModelUiState = ModelUiState.Importing(copiedBytes = 0L, totalBytes = 100L, originalName = "model.bin")
        var isActiveBeforeFinalizer: Boolean? = null
        var caughtException: CancellationException? = null
        var reThrownException: CancellationException? = null
        var loadMetadataCalled = false
        var cleanTempFilesCalled = false
        var updateCount = 0

        val job = scope.launch {
            try {
                taskStarted.complete(Unit)
                cancelBarrier.await()
            } catch (e: CancellationException) {
                isActiveBeforeFinalizer = currentCoroutineContext().isActive
                caughtException = e

                try {
                    ModelImportFinalizer.handleImportCancellation(
                        cancellationException = e,
                        loadMetadata = {
                            loadMetadataCalled = true
                            currentCoroutineContext().ensureActive()
                            ModelLoadResult.NoModel
                        },
                        cleanTempFiles = { activeFileName ->
                            cleanTempFilesCalled = true
                            currentCoroutineContext().ensureActive()
                            assertNull(activeFileName)
                        },
                        updateUiState = { newState ->
                            updateCount++
                            finalUiState = newState
                        }
                    )
                } catch (reThrown: CancellationException) {
                    reThrownException = reThrown
                    throw reThrown
                }
            }
        }

        try {
            withTimeout(5000) {
                taskStarted.await()

                val cancelReason = CancellationException("External cancel requested")
                job.cancel(cancelReason)

                job.join()
            }
        } finally {
            cancelBarrier.complete(Unit)
            scope.cancel()
        }

        assertEquals(false, isActiveBeforeFinalizer)
        assertNotNull(caughtException)
        assertEquals(caughtException, reThrownException)
        assertTrue(loadMetadataCalled)
        assertTrue(cleanTempFilesCalled)
        assertEquals(1, updateCount)
        assertEquals(ModelUiState.NoModel, finalUiState)
        assertTrue(job.isCancelled)
    }

    @Test
    fun testJ_FinalizerUnderCoordinatorHoldsLockUntilFinalizerCompletes() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val operationStarted = CompletableDeferred<Unit>()
        val cancelBarrier = CompletableDeferred<Unit>()
        val finalizerEntered = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()

        var finalUiState: ModelUiState = ModelUiState.Importing(copiedBytes = 0L, totalBytes = 100L, originalName = "model.bin")
        var updateCount = 0
        var rejectedLlmTestCallCount = 0

        val job1 = coordinator.tryRunOperation(scope, OperationType.IMPORT) {
            try {
                operationStarted.complete(Unit)
                cancelBarrier.await()
            } catch (e: CancellationException) {
                ModelImportFinalizer.handleImportCancellation(
                    cancellationException = e,
                    loadMetadata = {
                        finalizerEntered.complete(Unit)
                        releaseFinalizer.await()
                        ModelLoadResult.NoModel
                    },
                    cleanTempFiles = {},
                    updateUiState = { newState ->
                        updateCount++
                        finalUiState = newState
                    }
                )
            }
        }

        try {
            withTimeout(5000) {
                operationStarted.await()

                val cancelReason = CancellationException("External import cancel")
                job1?.cancel(cancelReason)

                finalizerEntered.await()

                assertTrue(coordinator.isBusy())
                assertEquals(OperationType.IMPORT, coordinator.activeType())
                assertEquals(0, updateCount)

                val job2 = coordinator.tryRunOperation(
                    scope,
                    OperationType.LLM_TEST,
                    onRejected = { rejectedLlmTestCallCount++ }
                ) {
                    fail("Conflicting LLM test should not execute while finalizer is running")
                }

                assertNull(job2)
                assertEquals(1, rejectedLlmTestCallCount)

                releaseFinalizer.complete(Unit)

                job1?.join()

                assertFalse(coordinator.isBusy())
                assertNull(coordinator.activeType())
                assertEquals(1, updateCount)
                assertEquals(ModelUiState.NoModel, finalUiState)
                assertTrue(job1?.isCancelled == true)

                var secondOpExecuted = false
                val job3 = coordinator.tryRunOperation(scope, OperationType.LLM_TEST) {
                    secondOpExecuted = true
                }
                job3?.join()
                assertTrue(secondOpExecuted)
            }
        } finally {
            cancelBarrier.complete(Unit)
            releaseFinalizer.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun testK_AssertionErrorInFinalizerPropagatesWithoutBeingSwallowed() = runBlocking {
        val originException = CancellationException("Import cancelled")

        try {
            ModelImportFinalizer.handleImportCancellation(
                cancellationException = originException,
                loadMetadata = { throw AssertionError("Critical Assertion Failure") },
                cleanTempFiles = {},
                updateUiState = {}
            )
            fail("AssertionError should have been thrown")
        } catch (e: Throwable) {
            assertTrue(e is AssertionError)
            assertEquals("Critical Assertion Failure", e.message)
        }
    }
}
