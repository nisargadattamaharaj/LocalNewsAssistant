package com.example.localnewsassistant

import com.example.localnewsassistant.data.local.NewsDao
import com.example.localnewsassistant.data.local.NewsSearchReader
import com.example.localnewsassistant.data.local.StoredNewsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class NewsSearchReaderTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private class NoCopyTestException(message: String) : Exception(message), CopyableThrowable<NoCopyTestException> {
        override fun createCopy(): NoCopyTestException? = null
    }

    private class FakeSearchNewsDao(
        var queryResults: List<StoredNewsEntity> = emptyList(),
        var queryException: Exception? = null,
        var onSearchInvoked: ((String) -> Unit)? = null,
        var capturedInsertedEntities: List<StoredNewsEntity>? = null,
        var cancelJobOnSearch: Boolean = false,
        var persistenceCalls: Int = 0
    ) : NewsDao() {
        override suspend fun insertOrUpdateEntities(entities: List<StoredNewsEntity>) {
            persistenceCalls++
            capturedInsertedEntities = entities
        }

        override suspend fun getByDatasetId(datasetId: String): List<StoredNewsEntity> {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getByCompositeKey(datasetId: String, messageId: String): StoredNewsEntity? {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getTotalCount(): Int {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun getCountByDatasetId(datasetId: String): Int {
            throw UnsupportedOperationException("Not implemented for test")
        }

        override suspend fun searchByText(escapedQuery: String): List<StoredNewsEntity> {
            onSearchInvoked?.invoke(escapedQuery)
            if (cancelJobOnSearch) {
                currentCoroutineContext().cancel()
            }
            queryException?.let { throw it }
            return queryResults
        }
    }

    @Test
    fun testA_constructionDoesNotInvokeDaoProviderOrSearch() {
        var providerInvoked = false
        var searchInvoked = false
        val fakeDao = FakeSearchNewsDao(
            onSearchInvoked = { searchInvoked = true }
        )
        val reader = NewsSearchReader(
            daoProvider = {
                providerInvoked = true
                fakeDao
            }
        )

        assertFalse("Constructing NewsSearchReader should not invoke daoProvider", providerInvoked)
        assertFalse("Constructing NewsSearchReader should not invoke fake search", searchInvoked)
    }

    @Test
    fun testB_blankQueryReturnsEmptyListWithoutCallingDao() {
        var providerInvoked = false
        val fakeDao = FakeSearchNewsDao(
            onSearchInvoked = { fail("DAO should not be invoked for blank query") }
        )
        val reader = NewsSearchReader(
            daoProvider = {
                providerInvoked = true
                fakeDao
            }
        )

        val resultEmpty = runBlocking { reader.search("") }
        val resultWhitespace = runBlocking { reader.search("   \t \n  ") }

        assertTrue(resultEmpty.isEmpty())
        assertTrue(resultWhitespace.isEmpty())
        assertFalse("Blank query must return emptyList without resolving daoProvider", providerInvoked)
    }

    @Test
    fun testC_queryBoundaryExact256AllowedAnd257ThrowsIllegalArgumentException() {
        var capturedQuery: String? = null
        val fakeDao = FakeSearchNewsDao(
            onSearchInvoked = { capturedQuery = it }
        )
        val reader = NewsSearchReader(
            daoProvider = { fakeDao }
        )

        // Exact 256 chars is allowed
        val exact256Query = "a".repeat(256)
        val result256 = runBlocking { reader.search(exact256Query) }
        assertNotNull(result256)
        assertEquals("a".repeat(256), capturedQuery)

        // 257 chars throws
        val longQuery = "a".repeat(257)
        try {
            runBlocking { reader.search(longQuery) }
            fail("Expected IllegalArgumentException for query longer than 256 chars")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("256") == true)
        }
    }

    @Test
    fun testD_queryWithNulCharacterThrowsIllegalArgumentException() {
        var providerInvoked = false
        val fakeDao = FakeSearchNewsDao()
        val reader = NewsSearchReader(
            daoProvider = {
                providerInvoked = true
                fakeDao
            }
        )

        val nulQuery = "новости\u0000сегодня"

        try {
            runBlocking { reader.search(nulQuery) }
            fail("Expected IllegalArgumentException for query containing NUL character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("NUL") == true)
        }
        assertFalse("NUL query must throw before resolving daoProvider", providerInvoked)
    }

    @Test
    fun testE_sanitizesWildcardsCorrectly() {
        val testInput = "100%_новости\\срочно'\""
        val expectedSanitized = "100\\%\\_новости\\\\срочно'\""

        val actualSanitized = NewsSearchReader.sanitizeQueryForLike(testInput)

        assertEquals(expectedSanitized, actualSanitized)
    }

    @Test
    fun testF_validQueryInvokesDaoOnInjectedDispatcherAndEscapesPattern() {
        var capturedEscapedQuery: String? = null
        val expectedEntity = StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_1",
            source = "Telegram",
            publishedAt = "2026-10-20T10:00:00Z",
            text = "100%_новости\\срочно'\"",
            synthetic = true
        )

        val workerThreadRef = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            val thread = Thread(runnable, "test-search-worker-thread")
            workerThreadRef.set(thread)
            thread
        }
        val customDispatcher = executor.asCoroutineDispatcher()

        try {
            val callerThread = Thread.currentThread()
            var providerThread: Thread? = null
            var queryEntryThread: Thread? = null

            val fakeDao = FakeSearchNewsDao(
                queryResults = listOf(expectedEntity),
                onSearchInvoked = { escaped ->
                    queryEntryThread = Thread.currentThread()
                    capturedEscapedQuery = escaped
                }
            )

            val reader = NewsSearchReader(
                daoProvider = {
                    providerThread = Thread.currentThread()
                    fakeDao
                },
                ioDispatcher = customDispatcher
            )

            val results = runBlocking { reader.search("  100%_новости\\срочно'\"  ") }

            val workerThread = workerThreadRef.get()
            assertNotNull("Worker thread should be captured", workerThread)
            assertEquals("100\\%\\_новости\\\\срочно'\"", capturedEscapedQuery)
            assertEquals(1, results.size)
            assertEquals(expectedEntity, results[0])

            assertSame("Provider resolution should run on worker thread", workerThread, providerThread)
            assertSame("Query execution should run on worker thread", workerThread, queryEntryThread)
            assertNotSame("Provider thread should not be test caller thread", callerThread, providerThread)
            assertNotSame("Query thread should not be test caller thread", callerThread, queryEntryThread)
        } finally {
            customDispatcher.close()
            executor.shutdown()
        }
    }

    @Test
    fun testG_providerAndDaoExceptionsArePropagatedWithoutConversionToEmptyList() {
        val expectedException = NoCopyTestException("Database error")
        val fakeDao = FakeSearchNewsDao(queryException = expectedException)
        val reader = NewsSearchReader(daoProvider = { fakeDao })

        try {
            runBlocking { reader.search("новости") }
            fail("Expected NoCopyTestException from DAO")
        } catch (e: Exception) {
            assertSame("Exceptions must be propagated directly without converting to emptyList", expectedException, e)
        }
    }

    @Test
    fun testH_cancellationPropagatedWithoutReturningEmptyList() {
        val cancellationException = CancellationException("Search cancelled")
        val fakeDao = FakeSearchNewsDao(queryException = cancellationException)
        val reader = NewsSearchReader(daoProvider = { fakeDao })

        try {
            runBlocking { reader.search("новости") }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Search cancelled", e.message)
        }
    }

    @Test
    fun testI_daoUpsertAllBoundaryAlwaysRecalculatesNormalizedText() = runBlocking {
        val fakeDao = FakeSearchNewsDao()

        val initialEntity = StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_100",
            source = "Source_Channel",
            publishedAt = "2026-10-21T10:00:00Z",
            text = "Первоначальный Текст",
            normalizedText = "первоначальный текст",
            synthetic = true
        )

        // Perform copy with NEW text but OLD/stale normalizedText
        val updatedEntity = initialEntity.copy(
            text = "Новый Текст Ёлка 100%",
            normalizedText = "СТАРЫЙ_НОРМАЛИЗОВАННЫЙ_ТЕКСТ"
        )

        val inputList = listOf(updatedEntity)
        fakeDao.upsertAll(inputList)

        assertEquals("Exactly one batch persistence call must be made", 1, fakeDao.persistenceCalls)

        val captured = fakeDao.capturedInsertedEntities
        assertNotNull("Captured inserted entities must not be null", captured)
        assertEquals("Exactly one entity in batch must be passed to insertOrUpdateEntities", 1, captured!!.size)

        val prepared = captured[0]

        // Metadata and original input objects MUST remain intact
        assertEquals("dataset_1", prepared.datasetId)
        assertEquals("msg_100", prepared.messageId)
        assertEquals("Source_Channel", prepared.source)
        assertEquals("2026-10-21T10:00:00Z", prepared.publishedAt)
        assertEquals(true, prepared.synthetic)
        assertEquals("Новый Текст Ёлка 100%", prepared.text)

        // Input object in caller list was NOT mutated
        assertEquals("СТАРЫЙ_НОРМАЛИЗОВАННЫЙ_ТЕКСТ", inputList[0].normalizedText)

        // normalizedText in prepared entity was recomputed by NewsDao boundary
        assertEquals("новый текст елка 100%", prepared.normalizedText)
    }

    @Test
    fun testJ1_providerExceptionPropagatedBeforeDaoCall() {
        val expectedException = NoCopyTestException("Provider resolution failed")
        val reader = NewsSearchReader(
            daoProvider = { throw expectedException }
        )

        try {
            runBlocking { reader.search("новости") }
            fail("Expected NoCopyTestException from provider")
        } catch (e: Exception) {
            assertSame("Provider exception must be propagated directly", expectedException, e)
        }
    }

    @Test
    fun testJ2_queryWithUppercaseYoAndMixedTextNormalizedAndPatternEscaped() = runBlocking {
        var capturedQuery: String? = null
        val fakeDao = FakeSearchNewsDao(
            onSearchInvoked = { capturedQuery = it }
        )
        val reader = NewsSearchReader(daoProvider = { fakeDao })

        // Query contains leading/trailing spaces, uppercase Cyrillic 'Ё', latin 'NEWS', wildcards '%' and '_'
        reader.search("  ЁЛКА 100%_NEWS  ")

        // trim() removes leading/trailing spaces, normalize converts to "елка 100%_news", sanitize escapes wildcards
        assertEquals("елка 100\\%\\_news", capturedQuery)
    }

    @Test
    fun testJ3A_preCancelledContextThrowsCancellationExceptionInReaderEntry() = runBlocking {
        var providerInvoked = false
        val fakeDao = FakeSearchNewsDao(
            onSearchInvoked = { fail("DAO must not be called for cancelled blank search") }
        )
        val reader = NewsSearchReader(daoProvider = {
            providerInvoked = true
            fakeDao
        })
        val cancelledParent = Job()
        cancelledParent.cancel()
        var entered = false
        var returnedNormally = false
        var cancellationObservedInBody = false
        val child = launch(cancelledParent, start = CoroutineStart.UNDISPATCHED) {
            entered = true
            try {
                reader.search("  \t  ")
                returnedNormally = true
            } catch (_: CancellationException) {
                cancellationObservedInBody = true
            }
        }
        child.join()
        assertTrue("Cancelled coroutine body must actually execute", entered)
        assertTrue("Reader must throw inside this body", cancellationObservedInBody)
        assertFalse("Reader must not return emptyList successfully", returnedNormally)
        assertFalse("Provider must not be resolved", providerInvoked)
    }

    @Test
    fun testJ3B_fakeDaoCancelsJobAndReturnsLateListDoesNotPublishResult() {
        val lateEntity = StoredNewsEntity(
            datasetId = "dataset_1",
            messageId = "msg_1",
            source = "Source",
            publishedAt = "2026-10-21T10:00:00Z",
            text = "Поздний результат",
            synthetic = true
        )

        var daoReached = false
        val fakeDao = FakeSearchNewsDao(
            queryResults = listOf(lateEntity),
            cancelJobOnSearch = true,
            onSearchInvoked = { daoReached = true }
        )
        val reader = NewsSearchReader(daoProvider = { fakeDao })

        try {
            runBlocking {
                reader.search("новости")
            }
            fail("Expected CancellationException when fake DAO cancels job in-flight")
        } catch (e: CancellationException) {
            assertNotNull(e)
        }

        assertTrue("Fake DAO searchByText must be reached before cancellation", daoReached)
    }
}
