package com.rork.rgdsartworkprep.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour this whole pass exists to guarantee: a slow or problematic ROM must
 * never prevent another ROM from being processed.
 *
 * These run on the JVM with no device because [ScanQueue] is deliberately pure.
 */
class ScanQueueTest {

    private fun queueOf(vararg names: String, policy: RetryPolicy = RetryPolicy()): ScanQueue =
        ScanQueue(
            names.mapIndexed { index, name ->
                QueueJob(id = "id$index", fileName = name, systemKey = "nes")
            },
            policy,
        )

    @Test
    fun `fast rom completes`() {
        val queue = queueOf("a.nes")
        val next = queue.nextReady(0L)
        assertEquals("id0", next?.id)

        queue.markProcessing("id0", 0L)
        queue.markCompleted("id0", elapsedMillis = 1_200L)

        assertEquals(JobState.Completed, queue.job("id0")?.state)
        assertEquals(1, queue.completedCount)
        assertTrue(queue.isDrained)
    }

    @Test
    fun `slow rom becomes deferred rather than failing`() {
        val queue = queueOf("slow.pbp")
        queue.markProcessing("id0", 0L)
        queue.markDeferred("id0", DeferReason.SlowResponse, nowMillis = 0L, elapsedMillis = 240_000L)

        val job = queue.job("id0")
        assertEquals(JobState.Deferred, job?.state)
        assertEquals(DeferReason.SlowResponse, job?.deferReason)
        // Deferred is explicitly not a permanent outcome.
        assertFalse(job!!.state.isFinished)
    }

    /**
     * The core regression. B is deferred; C and D must still be served immediately
     * rather than waiting behind it.
     */
    @Test
    fun `later roms continue processing while an earlier one is deferred`() {
        val queue = queueOf("a.nes", "b.pbp", "c.nes", "d.nes")

        queue.markProcessing("id0", 0L)
        queue.markCompleted("id0", 500L)

        queue.markProcessing("id1", 500L)
        queue.markDeferred("id1", DeferReason.ProviderTimeout, nowMillis = 500L, elapsedMillis = 40_000L)

        // Immediately afterwards, the queue offers C — not the deferred B.
        val afterDefer = queue.nextReady(600L)
        assertEquals("id2", afterDefer?.id)

        queue.markProcessing("id2", 600L)
        queue.markCompleted("id2", 400L)

        val next = queue.nextReady(700L)
        assertEquals("id3", next?.id)
        queue.markProcessing("id3", 700L)
        queue.markCompleted("id3", 400L)

        assertEquals(3, queue.completedCount)
        assertEquals(1, queue.deferredCount)
    }

    @Test
    fun `deferred rom is not served before its backoff expires`() {
        val policy = RetryPolicy(connectionBackoffMillis = listOf(5_000L))
        val queue = queueOf("a.nes", policy = policy)

        queue.markProcessing("id0", 0L)
        queue.markDeferred("id0", DeferReason.NetworkFailure, nowMillis = 0L, elapsedMillis = 100L)

        assertNull("too early to retry", queue.nextReady(4_999L))
        assertNotNull("eligible once the backoff passes", queue.nextReady(5_000L))
        assertEquals(5_000L, queue.nextEligibleAt())
    }

    @Test
    fun `deferred rom is retried and can then succeed`() {
        val policy = RetryPolicy(connectionBackoffMillis = listOf(1_000L))
        val queue = queueOf("a.nes", policy = policy)

        queue.markProcessing("id0", 0L)
        queue.markDeferred("id0", DeferReason.NetworkFailure, nowMillis = 0L, elapsedMillis = 100L)

        val retry = queue.nextReady(1_000L)
        assertEquals("id0", retry?.id)
        queue.markProcessing("id0", 1_000L)
        queue.markCompleted("id0", 800L)

        assertEquals(JobState.Completed, queue.job("id0")?.state)
        // Both attempts are counted, which is what the report quotes.
        assertEquals(2, queue.job("id0")?.attempts)
        assertEquals(900L, queue.job("id0")?.totalElapsedMillis)
    }

    /** A deferred job must never loop forever: the attempt ceiling ends it. */
    @Test
    fun `deferred rom gives up after the attempt ceiling`() {
        val policy = RetryPolicy(maxAttempts = 3, connectionBackoffMillis = listOf(0L))
        val queue = queueOf("a.nes", policy = policy)

        repeat(3) { attempt ->
            val ready = queue.nextReady(attempt.toLong())
            assertNotNull("attempt ${attempt + 1} should be offered", ready)
            queue.markProcessing("id0", attempt.toLong())
            queue.markDeferred(
                "id0",
                DeferReason.NetworkFailure,
                nowMillis = attempt.toLong(),
                elapsedMillis = 10L,
            )
        }

        assertEquals(JobState.Failed, queue.job("id0")?.state)
        assertEquals(3, queue.job("id0")?.attempts)
        // Nothing further is offered, at any time, so the loop terminates.
        assertNull(queue.nextReady(Long.MAX_VALUE))
        assertTrue(queue.isDrained)
    }

    @Test
    fun `permanent not found does not retry`() {
        val queue = queueOf("obscure.nes")
        queue.markProcessing("id0", 0L)
        queue.markSkipped("id0", 900L, "No match")

        assertEquals(JobState.Skipped, queue.job("id0")?.state)
        assertNull(queue.nextReady(Long.MAX_VALUE))
        assertEquals(0, queue.deferredCount)
    }

    @Test
    fun `unsupported system does not retry`() {
        val queue = ScanQueue(
            listOf(QueueJob(id = "id0", fileName = "game.arcade", systemKey = "mame")),
        )
        queue.markProcessing("id0", 0L)
        queue.markSkipped("id0", 5L, "MAME lookup is not enabled yet")

        assertEquals(JobState.Skipped, queue.job("id0")?.state)
        assertNull(queue.nextReady(Long.MAX_VALUE))
        assertTrue(queue.isDrained)
    }

    @Test
    fun `manual retry revives a job that gave up and resets its attempts`() {
        val policy = RetryPolicy(maxAttempts = 1)
        val queue = queueOf("a.nes", policy = policy)
        queue.markProcessing("id0", 0L)
        queue.markDeferred("id0", DeferReason.ServerFailure, nowMillis = 0L, elapsedMillis = 10L)
        assertEquals(JobState.Failed, queue.job("id0")?.state)

        queue.requeue(listOf("id0"))

        assertEquals(JobState.Ready, queue.job("id0")?.state)
        assertEquals(0, queue.job("id0")?.attempts)
        assertNotNull(queue.nextReady(0L))
    }

    /** Artwork already saved must never be fetched a second time. */
    @Test
    fun `manual retry never revives completed work`() {
        val queue = queueOf("a.nes")
        queue.markProcessing("id0", 0L)
        queue.markCompleted("id0", 100L)

        queue.requeue(listOf("id0"))

        assertEquals(JobState.Completed, queue.job("id0")?.state)
        assertNull(queue.nextReady(Long.MAX_VALUE))
    }

    @Test
    fun `clearing the queue leaves completed work untouched`() {
        val queue = queueOf("done.nes", "queued.nes")
        queue.markProcessing("id0", 0L)
        queue.markCompleted("id0", 100L)
        queue.markProcessing("id1", 0L)
        queue.markDeferred("id1", DeferReason.RateLimited, nowMillis = 0L, elapsedMillis = 10L)

        queue.clearDeferred()

        assertEquals(JobState.Completed, queue.job("id0")?.state)
        assertEquals(JobState.Skipped, queue.job("id1")?.state)
        assertEquals(0, queue.deferredCount)
    }

    @Test
    fun `rate limiting waits longer than a connection failure`() {
        val policy = RetryPolicy(
            connectionBackoffMillis = listOf(5_000L),
            serverBackoffMillis = listOf(15_000L),
        )
        assertEquals(5_000L, policy.nextEligibleAt(0L, attempts = 1, reason = DeferReason.NetworkFailure))
        assertEquals(15_000L, policy.nextEligibleAt(0L, attempts = 1, reason = DeferReason.RateLimited))
        assertEquals(15_000L, policy.nextEligibleAt(0L, attempts = 1, reason = DeferReason.ServerFailure))
    }

    @Test
    fun `backoff grows with each attempt`() {
        val policy = RetryPolicy(connectionBackoffMillis = listOf(5_000L, 15_000L, 30_000L))
        assertEquals(5_000L, policy.nextEligibleAt(0L, 1, DeferReason.NetworkFailure))
        assertEquals(15_000L, policy.nextEligibleAt(0L, 2, DeferReason.NetworkFailure))
        assertEquals(30_000L, policy.nextEligibleAt(0L, 3, DeferReason.NetworkFailure))
        // Beyond the schedule it holds at the longest wait rather than failing.
        assertEquals(30_000L, policy.nextEligibleAt(0L, 9, DeferReason.NetworkFailure))
    }

    @Test
    fun `finishing pending work leaves decided work alone`() {
        val queue = queueOf("done.nes", "running.nes", "waiting.nes")
        queue.markProcessing("id0", 0L)
        queue.markCompleted("id0", 100L)
        queue.markProcessing("id1", 0L)

        queue.finishPending(JobState.Skipped, "Scan cancelled")

        assertEquals(JobState.Completed, queue.job("id0")?.state)
        assertEquals(JobState.Skipped, queue.job("id1")?.state)
        assertEquals(JobState.Skipped, queue.job("id2")?.state)
    }
}
