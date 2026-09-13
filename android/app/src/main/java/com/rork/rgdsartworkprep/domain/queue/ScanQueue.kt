package com.rork.rgdsartworkprep.domain.queue

/**
 * The order in which ROMs are attempted, and what becomes of each one.
 *
 * This is deliberately pure: no Android, no coroutines, no network. Every rule that
 * decides whether a slow game blocks the scan lives here, which is what makes those
 * rules testable on the JVM without a device.
 *
 * The single promise it makes is that [nextReady] never returns a job that is
 * waiting on a backoff, so a difficult game can never be the reason a healthy one
 * goes unprocessed.
 */
class ScanQueue(
    jobs: List<QueueJob>,
    val policy: RetryPolicy = RetryPolicy(),
) {

    private val order: MutableList<String> = jobs.map { it.id }.toMutableList()
    private val jobsById: LinkedHashMap<String, QueueJob> =
        LinkedHashMap<String, QueueJob>().apply { jobs.forEach { put(it.id, it) } }

    val jobs: List<QueueJob> get() = order.mapNotNull { jobsById[it] }

    val size: Int get() = order.size

    fun job(id: String): QueueJob? = jobsById[id]

    fun countOf(state: JobState): Int = jobsById.values.count { it.state == state }

    val completedCount: Int get() = countOf(JobState.Completed)
    val skippedCount: Int get() = countOf(JobState.Skipped)
    val deferredCount: Int get() = countOf(JobState.Deferred)
    val failedCount: Int get() = countOf(JobState.Failed)

    /** Jobs that have been decided one way or another. */
    val finishedCount: Int get() = jobsById.values.count { it.state.isFinished }

    /** True when nothing is left that could still change on its own. */
    val isDrained: Boolean get() = jobsById.values.none { it.state.isPending }

    /**
     * The next job to work on, or null when nothing can be attempted right now.
     *
     * Fresh work always comes before deferred work: a job that has never been tried
     * is strictly more likely to succeed quickly than one that already failed, and
     * running the ready queue to exhaustion first is what guarantees a slow game
     * cannot hold up a fast one. Only once no [JobState.Ready] job remains does the
     * queue start serving deferred jobs whose backoff has expired.
     */
    fun nextReady(nowMillis: Long): QueueJob? =
        firstInOrder { it.state == JobState.Ready }
            ?: firstInOrder { it.isEligible(nowMillis) }

    /**
     * When the earliest deferred job becomes eligible, or null if none are waiting.
     *
     * The caller uses this to sleep exactly as long as needed instead of polling.
     */
    fun nextEligibleAt(): Long? = jobsById.values
        .filter { it.state == JobState.Deferred }
        .mapNotNull { it.nextEligibleAtMillis }
        .minOrNull()

    private inline fun firstInOrder(predicate: (QueueJob) -> Boolean): QueueJob? {
        order.forEach { id ->
            val job = jobsById[id] ?: return@forEach
            if (predicate(job)) return job
        }
        return null
    }

    /** Marks a job as being worked on and counts the attempt. */
    fun markProcessing(id: String, nowMillis: Long): QueueJob? = update(id) {
        it.copy(
            state = JobState.Processing,
            attempts = it.attempts + 1,
            lastAttemptAtMillis = nowMillis,
            nextEligibleAtMillis = null,
        )
    }

    fun markCompleted(id: String, elapsedMillis: Long, detail: String? = null): QueueJob? =
        finish(id, JobState.Completed, elapsedMillis, detail)

    fun markSkipped(id: String, elapsedMillis: Long, detail: String? = null): QueueJob? =
        finish(id, JobState.Skipped, elapsedMillis, detail)

    /**
     * Sets a job aside so the rest of the queue can continue.
     *
     * If it has no automatic attempts left it becomes [JobState.Failed] instead —
     * this is the only thing standing between a deferred job and an infinite retry
     * loop, so the attempt ceiling is enforced here rather than by the caller.
     */
    fun markDeferred(
        id: String,
        reason: DeferReason,
        nowMillis: Long,
        elapsedMillis: Long,
        detail: String? = null,
        providerKey: String? = null,
    ): QueueJob? {
        val current = jobsById[id] ?: return null
        val exhausted = !policy.hasAttemptsLeft(current.attempts)
        return update(id) {
            it.copy(
                state = if (exhausted) JobState.Failed else JobState.Deferred,
                deferReason = reason,
                detail = detail,
                providerKey = providerKey,
                lastElapsedMillis = elapsedMillis,
                totalElapsedMillis = it.totalElapsedMillis + elapsedMillis,
                nextEligibleAtMillis = if (exhausted) {
                    null
                } else {
                    policy.nextEligibleAt(nowMillis, it.attempts, reason)
                },
            )
        }
    }

    /** Ends a job permanently with no further attempts, whatever its history. */
    fun markFailed(id: String, elapsedMillis: Long, detail: String? = null): QueueJob? =
        finish(id, JobState.Failed, elapsedMillis, detail)

    private fun finish(
        id: String,
        state: JobState,
        elapsedMillis: Long,
        detail: String?,
    ): QueueJob? = update(id) {
        it.copy(
            state = state,
            detail = detail ?: it.detail,
            deferReason = if (state == JobState.Completed) null else it.deferReason,
            lastElapsedMillis = elapsedMillis,
            totalElapsedMillis = it.totalElapsedMillis + elapsedMillis,
            nextEligibleAtMillis = null,
        )
    }

    /**
     * Puts finished jobs back in line at the user's request.
     *
     * Manual retry resets the attempt count, because the ceiling exists to stop the
     * app looping unattended — not to stop a person trying again. Completed jobs are
     * never revived: artwork already saved must not be fetched twice.
     */
    fun requeue(ids: Collection<String>): List<QueueJob> {
        val wanted = ids.toSet()
        return jobsById.values
            .filter { it.id in wanted && it.state != JobState.Completed }
            .mapNotNull { job ->
                update(job.id) {
                    it.copy(
                        state = JobState.Ready,
                        attempts = 0,
                        deferReason = null,
                        detail = null,
                        nextEligibleAtMillis = null,
                    )
                }
            }
    }

    /** Ids of every job that could still be retried by hand. */
    fun retryableIds(): List<String> = jobs
        .filter { it.state == JobState.Deferred || it.state == JobState.Failed }
        .map { it.id }

    /** Drops deferred and failed work without touching anything already decided. */
    fun clearDeferred(): List<String> {
        val cleared = jobs.filter { it.state == JobState.Deferred || it.state == JobState.Failed }
        cleared.forEach { job ->
            update(job.id) {
                it.copy(state = JobState.Skipped, detail = it.detail ?: "Removed from the retry queue")
            }
        }
        return cleared.map { it.id }
    }

    /**
     * Ends every unfinished job, used when the whole run stops early.
     *
     * Anything already completed or skipped keeps its outcome — cancelling a scan
     * must never rewrite work that had already succeeded.
     */
    fun finishPending(state: JobState, detail: String): List<String> {
        val pending = jobs.filter { it.state.isPending }
        pending.forEach { job ->
            update(job.id) { it.copy(state = state, detail = detail, nextEligibleAtMillis = null) }
        }
        return pending.map { it.id }
    }

    private fun update(id: String, transform: (QueueJob) -> QueueJob): QueueJob? {
        val current = jobsById[id] ?: return null
        val updated = transform(current)
        jobsById[id] = updated
        return updated
    }
}
