package com.example.localnewsassistant

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.localnewsassistant.ui.NewsCountUiState
import com.example.localnewsassistant.ui.NewsCountViewModel
import com.example.localnewsassistant.ui.NewsCountViewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewsCountViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testA_initialStateIsNotRequestedAndNoCallToDependency() {
        var fetchInvoked = false
        val viewModel = NewsCountViewModel(
            fetchCount = {
                fetchInvoked = true
                0
            }
        )

        assertTrue(viewModel.uiState.value is NewsCountUiState.NotRequested)
        assertFalse("Constructor/init must not invoke fetchCount dependency", fetchInvoked)
    }

    @Test
    fun testB_fetchNewsCountTransitionsToLoadingAndThenSuccess() = runTest(testDispatcher) {
        val viewModel = NewsCountViewModel(fetchCount = { 42 })

        viewModel.fetchNewsCount()
        val loadingState = viewModel.uiState.value
        assertTrue("State must transition to Loading immediately", loadingState is NewsCountUiState.Loading)
        assertNull("Initial Loading should have null lastSuccessfulCount", (loadingState as NewsCountUiState.Loading).lastSuccessfulCount)

        runCurrent()
        val finalState = viewModel.uiState.value
        assertTrue("State must transition to Success(42)", finalState is NewsCountUiState.Success)
        assertEquals(42, (finalState as NewsCountUiState.Success).count)
    }

    @Test
    fun testC_fetchNewsCountReturnsZeroAsValidSuccess() = runTest(testDispatcher) {
        val viewModel = NewsCountViewModel(fetchCount = { 0 })

        viewModel.fetchNewsCount()
        runCurrent()

        val finalState = viewModel.uiState.value
        assertTrue("0 count must be returned as valid Success(0)", finalState is NewsCountUiState.Success)
        assertEquals(0, (finalState as NewsCountUiState.Success).count)
    }

    @Test
    fun testD_concurrentFetchCallsBeforeSchedulerRunIgnored() = runTest(testDispatcher) {
        var callCount = 0
        val deferred = CompletableDeferred<Int>()

        val viewModel = NewsCountViewModel(
            fetchCount = {
                callCount++
                deferred.await()
            }
        )

        // Two fast clicks BEFORE runCurrent()
        viewModel.fetchNewsCount()
        viewModel.fetchNewsCount()
        assertTrue("State must transition to Loading immediately on first click", viewModel.uiState.value is NewsCountUiState.Loading)

        runCurrent()
        assertEquals("Duplicate call before scheduler run must be ignored", 1, callCount)

        deferred.complete(10)
        runCurrent()

        assertEquals(NewsCountUiState.Success(10), viewModel.uiState.value)
    }

    @Test
    fun testE_exceptionInFetchCountTransitionsToSafeErrorWithoutExposingRawException() = runTest(testDispatcher) {
        val viewModel = NewsCountViewModel(
            fetchCount = { throw IllegalStateException("Sensitive path /data/user/0/com.example/databases/news.db failed") }
        )

        viewModel.fetchNewsCount()
        runCurrent()

        val finalState = viewModel.uiState.value
        assertTrue("Exception must transition to Error", finalState is NewsCountUiState.Error)
        val errorState = finalState as NewsCountUiState.Error
        assertEquals("Не удалось получить количество записей", errorState.message)
        assertFalse("Error message must not expose raw exception text or paths", errorState.message.contains("news.db"))

        // Recovery: Next manual click succeeds
        val returnCount = 18
        val viewModel2 = NewsCountViewModel(fetchCount = { returnCount })
        viewModel2.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(18), viewModel2.uiState.value)
    }

    @Test
    fun testF_sameInstanceCancellationRestoresPreviousSuccessAndAllowsSubsequentFetch() = runTest(testDispatcher) {
        var returnVal = 15
        var shouldThrowCancellation = false
        val deferred = CompletableDeferred<Unit>()

        val viewModel = NewsCountViewModel(
            fetchCount = {
                if (shouldThrowCancellation) {
                    deferred.await()
                    throw kotlinx.coroutines.CancellationException("Query cancelled")
                }
                returnVal
            }
        )

        // Step 1: Success(15)
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(15), viewModel.uiState.value)

        // Step 2: Cancelled query on same instance
        shouldThrowCancellation = true
        viewModel.fetchNewsCount()
        assertTrue(viewModel.uiState.value is NewsCountUiState.Loading)

        deferred.complete(Unit)
        runCurrent()

        // Should restore previous Success(15) on same instance
        assertEquals(NewsCountUiState.Success(15), viewModel.uiState.value)

        // Step 3: Subsequent fetch on same instance succeeds with 20
        shouldThrowCancellation = false
        returnVal = 20
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(20), viewModel.uiState.value)
    }

    @Test
    fun testG_staleExecutionCancellingJobDoesNotPublishSuccessOnSameInstance() = runTest(testDispatcher) {
        var returnVal = 15
        var shouldCancelJob = false

        val viewModel = NewsCountViewModel(
            fetchCount = {
                if (shouldCancelJob) {
                    currentCoroutineContext().job.cancel()
                    99 // Stale return value
                } else {
                    returnVal
                }
            }
        )

        // Step 1: Initial Success(15)
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(15), viewModel.uiState.value)

        // Step 2: Stale job cancellation returning 99
        shouldCancelJob = true
        viewModel.fetchNewsCount()
        runCurrent()

        // Must NOT publish Success(99); must retain previous Success(15)
        assertEquals(NewsCountUiState.Success(15), viewModel.uiState.value)

        // Step 3: Subsequent fetch succeeds with 20
        shouldCancelJob = false
        returnVal = 20
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(20), viewModel.uiState.value)
    }

    @Test
    fun testH_dependencyCancelsJobAndThrowsExceptionAllowsSubsequentFetch() = runTest(testDispatcher) {
        var shouldFailWithCancellationAndException = false
        var returnCount = 30

        val viewModel = NewsCountViewModel(
            fetchCount = {
                if (shouldFailWithCancellationAndException) {
                    currentCoroutineContext().job.cancel()
                    throw RuntimeException("Secondary DB error after cancellation")
                }
                returnCount
            }
        )

        // Initial Success(30)
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(30), viewModel.uiState.value)

        // Cancel job & throw exception
        shouldFailWithCancellationAndException = true
        viewModel.fetchNewsCount()
        runCurrent()

        // Must restore previous Success(30) without perpetual Loading or false Error
        assertEquals(NewsCountUiState.Success(30), viewModel.uiState.value)

        // Next fetch succeeds
        shouldFailWithCancellationAndException = false
        returnCount = 35
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(35), viewModel.uiState.value)
    }

    @Test
    fun testI_staleCountPreservedInLoadingAndErrorAfterPreviousSuccess() = runTest(testDispatcher) {
        val deferredSuccess = CompletableDeferred<Int>()
        var throwError = false
        val deferredSecond = CompletableDeferred<Int>()

        val viewModel = NewsCountViewModel(
            fetchCount = {
                if (throwError) {
                    deferredSecond.await()
                    throw RuntimeException("Secondary DB failure")
                } else {
                    deferredSuccess.await()
                }
            }
        )

        // Step 1: Initial fetch succeeds with 25
        deferredSuccess.complete(25)
        viewModel.fetchNewsCount()
        runCurrent()
        assertEquals(NewsCountUiState.Success(25), viewModel.uiState.value)

        // Step 2: Second fetch starts -> Loading(lastSuccessfulCount = 25)
        throwError = true
        viewModel.fetchNewsCount()
        val loadingState = viewModel.uiState.value
        assertTrue(loadingState is NewsCountUiState.Loading)
        assertEquals(25, (loadingState as NewsCountUiState.Loading).lastSuccessfulCount)

        // Step 3: Second fetch fails -> Error("...", lastSuccessfulCount = 25)
        deferredSecond.complete(0)
        runCurrent()
        val errorState = viewModel.uiState.value
        assertTrue(errorState is NewsCountUiState.Error)
        assertEquals(25, (errorState as NewsCountUiState.Error).lastSuccessfulCount)
        assertEquals("Не удалось получить количество записей", errorState.message)
    }

    @Test
    fun testJ_viewModelStoreClearCancelsActiveQueryWithoutPublishingError() = runTest(testDispatcher) {
        val store = ViewModelStore()
        val deferred = CompletableDeferred<Int>()

        val factory = NewsCountViewModelFactory(
            fetchCount = { deferred.await() }
        )
        val provider = ViewModelProvider(store, factory)
        val viewModel = provider[NewsCountViewModel::class.java]

        viewModel.fetchNewsCount()
        runCurrent()
        assertTrue(viewModel.uiState.value is NewsCountUiState.Loading)

        // Clear ViewModelStore while query is in-flight
        store.clear()
        runCurrent()

        // Complete deferred after clear -> must not crash or set Error
        deferred.complete(50)
        runCurrent()

        assertEquals(NewsCountUiState.NotRequested, viewModel.uiState.value)
    }
}
