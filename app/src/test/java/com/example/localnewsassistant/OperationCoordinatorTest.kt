package com.example.localnewsassistant

import com.example.localnewsassistant.model.OperationCoordinator
import com.example.localnewsassistant.model.OperationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OperationCoordinatorTest {

    @Test
    fun testRequirementA_FirstStatementInBlockSeesBusyTypeAndOwnership() {
        val coordinator = OperationCoordinator()
        val unconfinedScope = CoroutineScope(Dispatchers.Unconfined + Job())

        var sawBusyInBlock = false
        var sawTypeInBlock: OperationType? = null

        coordinator.tryRunOperation(unconfinedScope, OperationType.SUMMARY) {
            sawBusyInBlock = coordinator.isBusy()
            sawTypeInBlock = coordinator.activeType()
        }

        assertTrue(sawBusyInBlock)
        assertEquals(OperationType.SUMMARY, sawTypeInBlock)
        assertFalse(coordinator.isBusy())
        unconfinedScope.cancel()
    }

    @Test
    fun testRequirementB_NestedTryRunOperationIsRejected() {
        val coordinator = OperationCoordinator()
        val unconfinedScope = CoroutineScope(Dispatchers.Unconfined + Job())

        var nestedRejected = false
        var nestedJob: Job? = null

        coordinator.tryRunOperation(unconfinedScope, OperationType.SUMMARY) {
            nestedJob = coordinator.tryRunOperation(
                unconfinedScope,
                OperationType.LLM_TEST,
                onRejected = { nestedRejected = true }
            ) {
                // Should not execute
            }
        }

        assertNull(nestedJob)
        assertTrue(nestedRejected)
        assertFalse(coordinator.isBusy())
        unconfinedScope.cancel()
    }

    @Test
    fun testRequirementC_MultiThreadedConcurrencyAcceptsAtMostOne() {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val startLatch = CountDownLatch(1)
        val acceptedCount = AtomicInteger(0)
        val threadCount = 10
        val finishLatch = CountDownLatch(threadCount)
        val blockBarrier = CompletableDeferred<Unit>()

        for (i in 0 until threadCount) {
            Thread {
                startLatch.await()
                val job = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
                    blockBarrier.await()
                }
                if (job != null) {
                    acceptedCount.incrementAndGet()
                }
                finishLatch.countDown()
            }.start()
        }

        startLatch.countDown()
        val completedInTime = finishLatch.await(5, TimeUnit.SECONDS)

        assertTrue(completedInTime)
        assertEquals(1, acceptedCount.get())

        blockBarrier.complete(Unit)
        runBlocking { delay(100) }
        assertFalse(coordinator.isBusy())
        scope.cancel()
    }

    @Test
    fun testRequirementD_InstantCompletionLeavesNoFalseBusyState() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val job = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
            // Instant completion
        }

        job?.join()
        assertFalse(coordinator.isBusy())
        assertNull(coordinator.activeType())
        scope.cancel()
    }

    @Test
    fun testRequirementE_CancellationBeforeStartReleasesOwnershipAndBlockNotRun() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.cancel() // Scope pre-cancelled

        var blockExecuted = false
        var earlyFailed = false

        val job = coordinator.tryRunOperation(
            scope,
            OperationType.SUMMARY,
            onEarlyStartFailed = { earlyFailed = true }
        ) {
            blockExecuted = true
        }

        assertNull(job)
        assertFalse(blockExecuted)
        assertTrue(earlyFailed)
        assertFalse(coordinator.isBusy())
    }

    @Test
    fun testRequirementF_PreCancelledScopeRejectsOperation() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.cancel()

        var rejectedOrFailed = false
        val job = coordinator.tryRunOperation(
            scope,
            OperationType.IMPORT,
            onEarlyStartFailed = { rejectedOrFailed = true }
        ) {
            // Should not run
        }

        assertNull(job)
        assertTrue(rejectedOrFailed)
        assertFalse(coordinator.isBusy())
    }

    @Test
    fun testRequirementG_UnexpectedErrorNotSwallowedAndLockReleased() = runBlocking {
        val coordinator = OperationCoordinator()
        val testSupervisor = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + testSupervisor)

        val job = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
            throw AssertionError("Test assertion failure")
        }

        job?.join()

        assertTrue(job?.isCancelled == true)
        assertFalse(coordinator.isBusy())

        // Ensure next operation works cleanly
        var secondExecuted = false
        val job2 = coordinator.tryRunOperation(scope, OperationType.IMPORT) {
            secondExecuted = true
        }
        job2?.join()

        assertTrue(secondExecuted)
        assertFalse(coordinator.isBusy())
        scope.cancel()
    }

    @Test
    fun testRequirementH_UncancelableRegionHoldsLockUntilCompletion() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val taskStarted = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        val uncancelableFinished = CompletableDeferred<Unit>()

        val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
            taskStarted.complete(Unit)
            withContext(NonCancellable) {
                releaseTask.await()
                uncancelableFinished.complete(Unit)
            }
        }

        taskStarted.await()

        // Cancel job1 while running
        job1?.cancel()

        // Lock MUST NOT be released while NonCancellable is still running
        var secondRunAccepted = false
        val job2 = coordinator.tryRunOperation(scope, OperationType.LLM_TEST) {
            secondRunAccepted = true
        }

        assertNull(job2)
        assertFalse(secondRunAccepted)
        assertTrue(coordinator.isBusy())

        releaseTask.complete(Unit)
        uncancelableFinished.await()
        job1?.join()

        // Now completed
        assertFalse(coordinator.isBusy())
        scope.cancel()
    }

    @Test
    fun testRequirementI_ConflictsAcrossTypesRejectedWithoutDependencies() = runBlocking {
        val coordinator = OperationCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val started = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()

        val job1 = coordinator.tryRunOperation(scope, OperationType.SUMMARY) {
            started.complete(Unit)
            blocker.await()
        }

        started.await()

        var importCalled = false
        var llmTestCalled = false
        var recoveryCalled = false

        assertNull(coordinator.tryRunOperation(scope, OperationType.IMPORT) { importCalled = true })
        assertNull(coordinator.tryRunOperation(scope, OperationType.LLM_TEST) { llmTestCalled = true })
        assertNull(coordinator.tryRunOperation(scope, OperationType.RECOVERY) { recoveryCalled = true })

        assertFalse(importCalled)
        assertFalse(llmTestCalled)
        assertFalse(recoveryCalled)

        blocker.complete(Unit)
        job1?.join()
        scope.cancel()
    }
}
