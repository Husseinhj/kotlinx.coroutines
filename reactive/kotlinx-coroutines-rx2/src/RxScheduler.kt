/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.rx2

import io.reactivex.*
import io.reactivex.disposables.*
import io.reactivex.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.util.concurrent.*
import kotlin.coroutines.*

/**
 * Converts an instance of [Scheduler] to an implementation of [CoroutineDispatcher]
 * and provides native support of [delay] and [withTimeout].
 */
public fun Scheduler.asCoroutineDispatcher(): CoroutineDispatcher =
    if (this is DispatcherScheduler) {
        dispatcher
    } else {
        SchedulerCoroutineDispatcher(this)
    }

/**
 * Converts an instance of [CoroutineDispatcher] to an implementation of [Scheduler].
 */
public fun CoroutineDispatcher.asScheduler(): Scheduler =
    if (this is SchedulerCoroutineDispatcher) {
        scheduler
    } else {
        DispatcherScheduler(this)
    }

private class DispatcherScheduler(internal val dispatcher: CoroutineDispatcher) : Scheduler() {

    private val schedulerJob = SupervisorJob()

    private val scope = CoroutineScope(schedulerJob + dispatcher + CoroutineExceptionHandler { _, throwable ->
        RxJavaPlugins.onError(throwable)
    })

    override fun scheduleDirect(block: Runnable): Disposable =
        scheduleDirect(block, 0, TimeUnit.MILLISECONDS)

    override fun scheduleDirect(block: Runnable, delay: Long, unit: TimeUnit): Disposable {
        if (!scope.isActive) return Disposables.disposed()
        val newBlock = RxJavaPlugins.onSchedule(block)
        return scope.launch {
            if (delay > 0) {
                delay(unit.toMillis(delay))
            }
            newBlock.run()
        }.asDisposable()
    }

    override fun createWorker(): Worker =
        DispatcherWorker(dispatcher, schedulerJob)

    override fun shutdown() {
        scope.cancel()
    }

    private class DispatcherWorker(private val dispatcher: CoroutineDispatcher, parentJob: Job) : Worker() {

        private val workerJob = SupervisorJob(parentJob)
        private val workerScope = CoroutineScope(workerJob + dispatcher)
        private val blockChannel = Channel<SchedulerChannelTask>(Channel.UNLIMITED)

        init {
            workerJob.invokeOnCompletion {
                blockChannel.close()
            }

            workerScope.launch {
                while (isActive) {
                    val task = blockChannel.receive()
                    task.execute()
                }
            }
        }

        override fun isDisposed(): Boolean = !workerScope.isActive

        override fun schedule(block: Runnable): Disposable =
            schedule(block, 0, TimeUnit.MILLISECONDS)

        override fun schedule(block: Runnable, delay: Long, unit: TimeUnit): Disposable {
            if (!workerScope.isActive) return Disposables.disposed()

            val newBlock = RxJavaPlugins.onSchedule(block)
            val task = SchedulerChannelTask(delay, newBlock, dispatcher, workerJob)
            blockChannel.offer(task)
            return task
        }

        override fun dispose() {
            workerScope.cancel()
            blockChannel.close()
        }
    }
}

/**
 * Represents a task to be queued sequentially on a [Channel] for a [Scheduler.Worker].
 *
 * Delayed tasks do not block [Channel] from processing other tasks
 */
private class SchedulerChannelTask(
    private val delayMillis: Long,
    private val block: Runnable,
    dispatcher: CoroutineDispatcher,
    parentJob: Job
) : Disposable {
    private val taskScope = CoroutineScope(dispatcher + Job(parentJob))
    private val delayJob: Job

    init {
        delayJob = taskScope.launch {
            delay(delayMillis)
        }
    }

    fun execute() {
        if (delayJob.isCompleted && taskScope.isActive) {
            block.run()
        } else {
            taskScope.launch {
                delayJob.join()
                yield()
                block.run()
            }
        }
    }

    override fun isDisposed(): Boolean =
        !taskScope.isActive

    override fun dispose() {
        taskScope.cancel()
    }
}

/**
 * Implements [CoroutineDispatcher] on top of an arbitrary [Scheduler].
 */
public class SchedulerCoroutineDispatcher(
    /**
     * Underlying scheduler of current [CoroutineDispatcher].
     */
    public val scheduler: Scheduler
) : CoroutineDispatcher(), Delay {
    /** @suppress */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        scheduler.scheduleDirect(block)
    }

    /** @suppress */
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val disposable = scheduler.scheduleDirect({
            with(continuation) { resumeUndispatched(Unit) }
        }, timeMillis, TimeUnit.MILLISECONDS)
        continuation.disposeOnCancellation(disposable)
    }

    /** @suppress */
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable): DisposableHandle {
        val disposable = scheduler.scheduleDirect(block, timeMillis, TimeUnit.MILLISECONDS)
        return DisposableHandle { disposable.dispose() }
    }

    /** @suppress */
    override fun toString(): String = scheduler.toString()
    /** @suppress */
    override fun equals(other: Any?): Boolean = other is SchedulerCoroutineDispatcher && other.scheduler === scheduler
    /** @suppress */
    override fun hashCode(): Int = System.identityHashCode(scheduler)
}

private class JobDisposable(private val job: Job) : Disposable {
    override fun isDisposed(): Boolean = job.isCancelled || job.isCompleted

    override fun dispose() {
        job.cancel()
    }
}

private fun Job.asDisposable(): Disposable = JobDisposable(this)
