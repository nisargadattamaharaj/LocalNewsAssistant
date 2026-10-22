package com.example.localnewsassistant

import com.example.localnewsassistant.data.local.NewsCountReader
import com.example.localnewsassistant.data.local.NewsDao
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class NewsCountReaderTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private class NoCopyTestException(message: String) : Exception(message), CopyableThrowable<NoCopyTestException> {
        override fun createCopy(): NoCopyTestException? = null
    }

    private class FakeNewsDao(
        var countResult: Int = 0,
        var queryException: Exception? = null,
        var onQueryInvoked: (() -> Unit)? = null
    ) : NewsDao() {
        override suspend fun insertOrUpdateEntities(entities: List<StoredNewsEntity>) {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getByDatasetId(datasetId: String): List<StoredNewsEntity> {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getByCompositeKey(datasetId: String, messageId: String): StoredNewsEntity? {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getTotalCount(): Int {
            onQueryInvoked?.invoke()
            queryException?.let { throw it }
            return countResult
        }

        override suspend fun getCountByDatasetId(datasetId: String): Int {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun searchByText(escapedQuery: String): List<StoredNewsEntity> {
            throw UnsupportedOperationException("Not implemented for test")
        }
    }

    @Test
    fun testA_constructionDoesNotInvokeDaoProviderOrQuery() {
        var providerInvoked = false
        var queryInvoked = false
        val fakeDao = FakeNewsDao(
            onQueryInvoked = { queryInvoked = true }
        )
        val reader = NewsCountReader(
            daoProvider = {
                providerInvoked = true
                fakeDao
            }
        )

        assertFalse("Constructing NewsCountReader should not invoke daoProvider", providerInvoked)
        assertFalse("Constructing NewsCountReader should not invoke fake query", queryInvoked)
    }

    @Test
    fun testB_zeroResultReturnedUnchanged() {
        val fakeDao = FakeNewsDao(countResult = 0)
        val reader = NewsCountReader(daoProvider = { fakeDao })

        val result = runBlocking { reader.getTotalCount() }

        assertEquals(0, result)
    }

    @Test
    fun testC_nonzeroResultReturnedUnchanged() {
        val fakeDao = FakeNewsDao(countResult = 42)
        val reader = NewsCountReader(daoProvider = { fakeDao })

        val result = runBlocking { reader.getTotalCount() }

        assertEquals(42, result)
    }

    @Test
    fun testD_providerExceptionPropagated() {
        val expectedException = NoCopyTestException("Provider failed")
        val reader = NewsCountReader(
            daoProvider = { throw expectedException }
        )

        try {
            runBlocking { reader.getTotalCount() }
            fail("Expected NoCopyTestException from provider")
        } catch (e: Exception) {
            assertSame("Expected identical exception instance to be propagated", expectedException, e)
        }
    }

    @Test
    fun testE_daoExceptionPropagated() {
        val expectedException = NoCopyTestException("Database error")
        val fakeDao = FakeNewsDao(queryException = expectedException)
        val reader = NewsCountReader(daoProvider = { fakeDao })

        try {
            runBlocking { reader.getTotalCount() }
            fail("Expected NoCopyTestException from DAO")
        } catch (e: Exception) {
            assertSame("Expected identical exception instance to be propagated", expectedException, e)
        }
    }

    @Test
    fun testF_cancellationExceptionPropagated() {
        val cancellationException = CancellationException("Query cancelled")
        val fakeDao = FakeNewsDao(queryException = cancellationException)
        val reader = NewsCountReader(daoProvider = { fakeDao })

        try {
            runBlocking { reader.getTotalCount() }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Query cancelled", e.message)
        }
    }

    @Test
    fun testG_providerAndQueryExecutionRunOnInjectedDispatcher() {
        val workerThreadRef = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            val thread = Thread(runnable, "test-io-worker-thread-G")
            workerThreadRef.set(thread)
            thread
        }
        val customDispatcher = executor.asCoroutineDispatcher()

        try {
            val callerThread = Thread.currentThread()
            var providerThread: Thread? = null
            var queryEntryThread: Thread? = null

            val fakeDao = FakeNewsDao(
                countResult = 15,
                onQueryInvoked = {
                    queryEntryThread = Thread.currentThread()
                }
            )
            val reader = NewsCountReader(
                daoProvider = {
                    providerThread = Thread.currentThread()
                    fakeDao
                },
                ioDispatcher = customDispatcher
            )

            val result = runBlocking { reader.getTotalCount() }

            val workerThread = workerThreadRef.get()
            assertNotNull("Worker thread should have been captured by ThreadFactory", workerThread)
            assertEquals(15, result)
            assertSame("Provider resolution should run on worker thread", workerThread, providerThread)
            assertSame("Query execution should run on worker thread", workerThread, queryEntryThread)
            assertNotSame("Provider thread should not be test caller thread", callerThread, providerThread)
            assertNotSame("Query thread should not be test caller thread", callerThread, queryEntryThread)
        } finally {
            customDispatcher.close()
            executor.shutdown()
        }
    }
}
