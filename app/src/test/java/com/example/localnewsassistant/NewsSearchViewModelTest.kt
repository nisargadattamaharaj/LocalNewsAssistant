package com.example.localnewsassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.localnewsassistant.data.local.StoredNewsEntity
import com.example.localnewsassistant.ui.NewsSearchUiState
import com.example.localnewsassistant.ui.NewsSearchViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import com.example.localnewsassistant.ui.NewsSearchViewModelFactory

@OptIn(ExperimentalCoroutinesApi::class)
class NewsSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestViewModelFactory(
        private val searchNews: suspend (String) -> List<StoredNewsEntity>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NewsSearchViewModel(searchNews) as T
        }
    }

    private fun rows72(key: String): List<StoredNewsEntity> = listOf(
        StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_$key",
            source = "SyntheticSource",
            publishedAt = "2026-10-21T10:00:00Z",
            text = "Synthetic $key",
            synthetic = true
        )
    )

    private suspend fun TestScope.checkSelfCancellation72(validation: Boolean) {
        val store = ViewModelStore()
        var cancelNow = true
        val vm = ViewModelProvider(store, TestViewModelFactory {
            if (cancelNow) {
                currentCoroutineContext().job.cancel()
                if (validation) throw IllegalArgumentException("SYNTHETIC_INTERNAL")
                throw IllegalStateException("SYNTHETIC_INTERNAL")
            }
            rows72("recovered")
        })[NewsSearchViewModel::class.java]
        try {
            vm.performSearch("cancel")
            runCurrent()
            assertEquals(NewsSearchUiState.NotRequested, vm.uiState.value)
            cancelNow = false
            vm.performSearch("recovered")
            runCurrent()
            assertEquals(NewsSearchUiState.Success("recovered", rows72("recovered")), vm.uiState.value)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun testR3_runtimeSelfCancellation72() = runTest(testDispatcher) {
        checkSelfCancellation72(validation = false)
    }

    @Test
    fun testR3_validationSelfCancellation72() = runTest(testDispatcher) {
        checkSelfCancellation72(validation = true)
    }

    private suspend fun TestScope.checkLateA72(bFinishesFirst: Boolean, failA: Boolean) {
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val store = ViewModelStore()
        var aStarted = false
        var bStarted = false
        var aReturned = false
        var aThrew = false
        var aJob: Job? = null
        val vm = ViewModelProvider(store, TestViewModelFactory { query ->
            when (query) {
                "A" -> {
                    aJob = currentCoroutineContext().job
                    aStarted = true
                    val result = withContext(NonCancellable) {
                        releaseA.await()
                        if (failA) {
                            aThrew = true
                            throw IllegalStateException("SYNTHETIC_LATE_A")
                        }
                        rows72("A")
                    }
                    aReturned = true
                    result
                }
                "B" -> {
                    bStarted = true
                    releaseB.await()
                    rows72("B")
                }
                else -> error("Unexpected synthetic query")
            }
        })[NewsSearchViewModel::class.java]
        val loadingB = NewsSearchUiState.Loading("B")
        val successB = NewsSearchUiState.Success("B", rows72("B"))
        try {
            vm.performSearch("A")
            runCurrent()
            assertTrue(aStarted)
            assertFalse(aReturned)
            assertFalse(aThrew)
            vm.performSearch("B")
            runCurrent()
            assertTrue(bStarted)
            assertTrue(aJob?.isCancelled == true)
            assertEquals(loadingB, vm.uiState.value)
            if (bFinishesFirst) {
                releaseB.complete(Unit)
                runCurrent()
                assertEquals(successB, vm.uiState.value)
            }
            assertFalse(aReturned)
            assertFalse(aThrew)
            releaseA.complete(Unit)
            runCurrent()
            if (failA) {
                assertTrue(aThrew)
                assertFalse(aReturned)
            } else {
                assertTrue(aReturned)
                assertFalse(aThrew)
            }
            assertEquals(if (bFinishesFirst) successB else loadingB, vm.uiState.value)
            if (!bFinishesFirst) {
                releaseB.complete(Unit)
                runCurrent()
                assertEquals(successB, vm.uiState.value)
            }
        } finally {
            releaseA.complete(Unit)
            releaseB.complete(Unit)
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun testT6_requestBLaunchedWhileAInFlightCancelsAAndBCompletesSuccessfully() = runTest(testDispatcher) {
        checkLateA72(bFinishesFirst = false, failA = false)
    }

    @Test
    fun testT7_requestBCompletedSuccessAndLateReturnAIsIgnored() = runTest(testDispatcher) {
        checkLateA72(bFinishesFirst = true, failA = false)
    }

    @Test
    fun testT8_lateExceptionFromAAfterCancellationDoesNotOverrideBState() = runTest(testDispatcher) {
        checkLateA72(bFinishesFirst = true, failA = true)
    }

    @Test
    fun testLateReturnWhileBLoading72() = runTest(testDispatcher) {
        checkLateA72(bFinishesFirst = false, failA = false)
    }

    @Test
    fun testLateErrorAfterBSuccess72() = runTest(testDispatcher) {
        checkLateA72(bFinishesFirst = true, failA = true)
    }

    @Test
    fun testBlankWhileLoading72() = runTest(testDispatcher) {
        val release = CompletableDeferred<Unit>()
        val store = ViewModelStore()
        var calls = 0
        var activeJob: Job? = null
        val vm = ViewModelProvider(store, TestViewModelFactory {
            calls++
            activeJob = currentCoroutineContext().job
            release.await()
            rows72("held")
        })[NewsSearchViewModel::class.java]
        try {
            vm.performSearch("held")
            runCurrent()
            val before = vm.uiState.value
            assertEquals(NewsSearchUiState.Loading("held"), before)
            assertEquals(1, calls)
            vm.performSearch("  ")
            runCurrent()
            assertEquals(before, vm.uiState.value)
            assertEquals(1, calls)
            assertTrue(activeJob?.isActive == true)
            release.complete(Unit)
            runCurrent()
            assertEquals(NewsSearchUiState.Success("held", rows72("held")), vm.uiState.value)
        } finally {
            release.complete(Unit)
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun testT1_initialStateIsNotRequestedAndNoCallToDependency() {
        var searchInvoked = false
        val viewModel = NewsSearchViewModel(
            searchNews = {
                searchInvoked = true
                emptyList()
            }
        )

        assertTrue("Initial state must be NotRequested", viewModel.uiState.value is NewsSearchUiState.NotRequested)
        assertFalse("Constructor/init must not invoke searchNews dependency", searchInvoked)
    }

    @Test
    fun testT2_blankQueryDoesNotCallDependencyOrChangeExistingState() = runTest(testDispatcher) {
        var searchCalls = 0
        val viewModel = NewsSearchViewModel(
            searchNews = {
                searchCalls++
                rows72("test")
            }
        )

        // Blank on initial state
        viewModel.performSearch("")
        viewModel.performSearch("   \t \n  ")
        assertEquals(0, searchCalls)
        assertTrue("State must remain NotRequested on blank query", viewModel.uiState.value is NewsSearchUiState.NotRequested)

        // Perform valid search to reach Success
        viewModel.performSearch("новости")
        runCurrent()
        val successState = viewModel.uiState.value
        assertTrue(successState is NewsSearchUiState.Success)
        assertEquals(1, searchCalls)

        // Blank query while in Success does NOT invoke dependency or change state
        viewModel.performSearch("   ")
        runCurrent()
        assertEquals(1, searchCalls)
        assertEquals("Blank query must preserve exact existing Success state", successState, viewModel.uiState.value)
    }

    @Test
    fun testT3_validQueryTransitionsToLoadingAndThenSuccessWithOriginalEntities() = runTest(testDispatcher) {
        var capturedQuery: String? = null
        val expectedEntity = StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_001",
            source = "Channel_1",
            publishedAt = "2026-10-21T10:00:00Z",
            text = "ЁЛКА свежая лесная",
            synthetic = true
        )

        val viewModel = NewsSearchViewModel(
            searchNews = { query ->
                capturedQuery = query
                listOf(expectedEntity)
            }
        )

        viewModel.performSearch("  ЁЖ NEWS  ")

        val loadingState = viewModel.uiState.value
        assertTrue("State must transition to Loading immediately", loadingState is NewsSearchUiState.Loading)
        assertEquals("ЁЖ NEWS", (loadingState as NewsSearchUiState.Loading).query)

        runCurrent()

        val finalState = viewModel.uiState.value
        assertTrue("State must transition to Success", finalState is NewsSearchUiState.Success)
        val successState = finalState as NewsSearchUiState.Success
        assertEquals("ЁЖ NEWS", capturedQuery)
        assertEquals("ЁЖ NEWS", successState.query)
        assertEquals(1, successState.results.size)
        assertEquals(expectedEntity, successState.results[0])
        assertEquals("Original text must be preserved", "ЁЛКА свежая лесная", successState.results[0].text)
    }

    @Test
    fun testT4_validQueryWithZeroMatchesTransitionsToEmptyResult() = runTest(testDispatcher) {
        val viewModel = NewsSearchViewModel(
            searchNews = { emptyList() }
        )

        viewModel.performSearch("несуществующий_запрос")
        runCurrent()

        val finalState = viewModel.uiState.value
        assertTrue("State must transition to EmptyResult", finalState is NewsSearchUiState.EmptyResult)
        assertEquals("несуществующий_запрос", (finalState as NewsSearchUiState.EmptyResult).query)
    }

    @Test
    fun testT5_exceptionsInDependencyTransitionToSafeErrorsAndSubsequentSearchSucceeds() = runTest(testDispatcher) {
        var failureMode = 1
        val viewModel = NewsSearchViewModel(
            searchNews = { _ ->
                when (failureMode) {
                    1 -> throw IllegalArgumentException("INTERNAL_VALIDATION_DETAIL /private/test-path failed")
                    2 -> throw RuntimeException("Database path /data/user/0/com.example/databases/news.db failed")
                    else -> rows72("ok")
                }
            }
        )

        // 1. IllegalArgumentException -> safe "Недопустимый поисковый запрос"
        viewModel.performSearch("запрос_1")
        runCurrent()

        val errorState1 = viewModel.uiState.value
        assertTrue("State must transition to Error on IllegalArgumentException", errorState1 is NewsSearchUiState.Error)
        val err1 = errorState1 as NewsSearchUiState.Error
        assertEquals("Недопустимый поисковый запрос", err1.message)
        assertFalse("Error message must not expose raw exception text", err1.message.contains("/private/test-path"))

        // 2. Generic Exception -> safe "Не удалось выполнить локальный поиск"
        failureMode = 2
        viewModel.performSearch("запрос_2")
        runCurrent()

        val errorState2 = viewModel.uiState.value
        assertTrue("State must transition to Error on generic Exception", errorState2 is NewsSearchUiState.Error)
        val err2 = errorState2 as NewsSearchUiState.Error
        assertEquals("Не удалось выполнить локальный поиск", err2.message)
        assertFalse("Error message must not expose raw exception text", err2.message.contains("news.db"))

        // 3. Recovery: Subsequent search on same ViewModel instance succeeds
        failureMode = 3
        viewModel.performSearch("новости")
        runCurrent()

        val recoveryState = viewModel.uiState.value
        assertTrue("Subsequent search must succeed", recoveryState is NewsSearchUiState.Success)
        assertEquals(1, (recoveryState as NewsSearchUiState.Success).results.size)
    }

    @Test
    fun testT9_activeDependencyCancellationResetsToNotRequestedAndAllowsSubsequentSearch() = runTest(testDispatcher) {
        var throwCancellation = false

        val viewModel = NewsSearchViewModel(
            searchNews = {
                if (throwCancellation) {
                    throw CancellationException("Search job cancelled")
                }
                rows72("1")
            }
        )

        throwCancellation = true
        viewModel.performSearch("новости")
        runCurrent()

        val stateAfterCancel = viewModel.uiState.value
        assertTrue("Cancellation must reset state to NotRequested", stateAfterCancel is NewsSearchUiState.NotRequested)

        // Subsequent search on same ViewModel instance succeeds
        throwCancellation = false
        viewModel.performSearch("новости")
        runCurrent()

        val stateAfterRecovery = viewModel.uiState.value
        assertTrue("Subsequent search on same instance must succeed", stateAfterRecovery is NewsSearchUiState.Success)
        assertEquals("Synthetic 1", (stateAfterRecovery as NewsSearchUiState.Success).results[0].text)
    }

    @Test
    fun testT10_viewModelStoreClearCancelsActiveSearchJobWithoutPublishingLateResult() = runTest(testDispatcher) {
        val store = ViewModelStore()
        val deferred = CompletableDeferred<List<StoredNewsEntity>>()
        var lateReturnReached = false

        val factory = NewsSearchViewModelFactory(
            searchNews = {
                try {
                    deferred.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        lateReturnReached = true
                        rows72("late")
                    }
                }
            }
        )
        val provider = ViewModelProvider(store, factory)
        val viewModel = provider[NewsSearchViewModel::class.java]

        viewModel.performSearch("новости")
        runCurrent()
        val stateBeforeClear = viewModel.uiState.value
        assertTrue("State must be Loading before clear", stateBeforeClear is NewsSearchUiState.Loading)

        // Clear ViewModelStore while search is in-flight
        store.clear()
        runCurrent()

        assertTrue("Late return path must be reached in test fake after store.clear()", lateReturnReached)
        // On cleared ViewModel, state remains exactly stateBeforeClear and MUST NOT publish Error or Success or reset
        assertEquals("Cleared ViewModel must NOT publish new state or error", stateBeforeClear, viewModel.uiState.value)
    }
}
