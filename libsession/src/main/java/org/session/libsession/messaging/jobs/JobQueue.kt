package org.session.libsession.messaging.jobs

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.session.libsession.messaging.MessagingConfiguration
import org.session.libsignal.utilities.logging.Log
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

class JobQueue : JobDelegate {
    private var hasResumedPendingJobs = false // Just for debugging
    private val jobTimestampMap = ConcurrentHashMap<Long, AtomicInteger>()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = GlobalScope + SupervisorJob()
    private val queue = Channel<Job>(UNLIMITED)

    val timer = Timer()

    init {
        // Process jobs
        scope.launch(dispatcher) {
            while (isActive) {
                queue.receive().let { job ->
                    job.delegate = this@JobQueue
                    job.execute()
                }
            }
        }
    }

    companion object {
        @JvmStatic
        val shared: JobQueue by lazy { JobQueue() }
    }

    fun add(job: Job) {
        addWithoutExecuting(job)
        queue.offer(job) // offer always called on unlimited capacity
    }

    private fun addWithoutExecuting(job: Job) {
        // When adding multiple jobs in rapid succession, timestamps might not be good enough as a unique ID. To
        // deal with this we keep track of the number of jobs with a given timestamp and add that to the end of the
        // timestamp to make it a unique ID. We can't use a random number because we do still want to keep track
        // of the order in which the jobs were added.
        val currentTime = System.currentTimeMillis()
        jobTimestampMap.putIfAbsent(currentTime, AtomicInteger())
        job.id = currentTime.toString() + jobTimestampMap[currentTime]!!.getAndIncrement().toString()

        MessagingConfiguration.shared.storage.persistJob(job)
    }

    fun resumePendingJobs() {
        if (hasResumedPendingJobs) {
            Log.d("Loki", "resumePendingJobs() should only be called once.")
            return
        }
        hasResumedPendingJobs = true
        val allJobTypes = listOf(AttachmentDownloadJob.KEY, AttachmentDownloadJob.KEY, MessageReceiveJob.KEY, MessageSendJob.KEY, NotifyPNServerJob.KEY)
        allJobTypes.forEach { type ->
            val allPendingJobs = MessagingConfiguration.shared.storage.getAllPendingJobs(type)
            allPendingJobs.sortedBy { it.id }.forEach { job ->
                Log.i("Jobs", "Resuming pending job of type: ${job::class.simpleName}.")
                queue.offer(job) // Offer always called on unlimited capacity
            }
        }
    }

    override fun handleJobSucceeded(job: Job) {
        MessagingConfiguration.shared.storage.markJobAsSucceeded(job)
    }

    override fun handleJobFailed(job: Job, error: Exception) {
        job.failureCount += 1
        val storage = MessagingConfiguration.shared.storage
        if (storage.isJobCanceled(job)) { return Log.i("Jobs", "${job::class.simpleName} canceled.")}
        storage.persistJob(job)
        if (job.failureCount == job.maxFailureCount) {
            storage.markJobAsFailed(job)
        } else {
            val retryInterval = getRetryInterval(job)
            Log.i("Jobs", "${job::class.simpleName} failed; scheduling retry (failure count is ${job.failureCount}).")
            timer.schedule(delay = retryInterval) {
                Log.i("Jobs", "Retrying ${job::class.simpleName}.")
                queue.offer(job)
            }
        }
    }

    override fun handleJobFailedPermanently(job: Job, error: Exception) {
        job.failureCount += 1
        val storage = MessagingConfiguration.shared.storage
        storage.persistJob(job)
        storage.markJobAsFailed(job)
    }

    private fun getRetryInterval(job: Job): Long {
        // Arbitrary backoff factor...
        // try  1 delay: 0.5s
        // try  2 delay: 1s
        // ...
        // try  5 delay: 16s
        // ...
        // try 11 delay: 512s
        val maxBackoff = (10 * 60).toDouble() // 10 minutes
        return (1000 * 0.25 * min(maxBackoff, (2.0).pow(job.failureCount))).roundToLong()
    }
}