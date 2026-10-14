package com.example.localnewsassistant.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class OperationType {
    RECOVERY,
    IMPORT,
    LLM_TEST,
    SUMMARY
}

class OperationCoordinator {

    private class ActiveOperation(
        val id: Long,
        val type: OperationType,
        val job: Job
    )

    private val operationCounter = AtomicLong(0L)
    @Volatile
    private var activeOp: ActiveOperation? = null

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun isBusy(): Boolean = activeOp != null

    fun activeType(): OperationType? = activeOp?.type

    fun tryRunOperation(
        scope: CoroutineScope,
        type: OperationType,
        onRejected: () -> Unit = {},
        onEarlyStartFailed: ((Throwable) -> Unit)? = null,
        block: suspend (opId: Long) -> Unit
    ): Job? {
        val nextId = operationCounter.incrementAndGet()
        var lazyJob: Job? = null

        synchronized(this) {
            if (activeOp != null) {
                onRejected()
                return null
            }

            if (!scope.isActive) {
                onEarlyStartFailed?.invoke(CancellationException("Scope is not active"))
                return null
            }

            val job = scope.launch(start = CoroutineStart.LAZY) {
                block(nextId)
            }

            val op = ActiveOperation(nextId, type, job)
            activeOp = op
            _isBusy.value = true

            job.invokeOnCompletion {
                synchronized(this@OperationCoordinator) {
                    if (activeOp?.id == nextId) {
                        activeOp = null
                        _isBusy.value = false
                    }
                }
            }

            lazyJob = job
        }

        val started = try {
            lazyJob?.start() ?: false
        } catch (e: Throwable) {
            synchronized(this) {
                if (activeOp?.id == nextId) {
                    activeOp = null
                    _isBusy.value = false
                }
            }
            onEarlyStartFailed?.invoke(e)
            return null
        }

        if (!started) {
            synchronized(this) {
                if (activeOp?.id == nextId) {
                    activeOp = null
                    _isBusy.value = false
                }
            }
            onEarlyStartFailed?.invoke(CancellationException("Job start failed"))
            return null
        }

        return lazyJob
    }
}
