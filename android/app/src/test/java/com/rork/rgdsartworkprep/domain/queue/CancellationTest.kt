package com.rork.rgdsartworkprep.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancelling is the user's decision, not a provider failure.
 *
 * These assert the two promises that matter: artwork already saved is left exactly
 * as it was, and nothing keeps running or retrying afterwards.
 */
class CancellationTest {

    private fun midRunQueue(): ScanQueue = ScanQueue(
        listOf(
            QueueJob("done", "done.nes", "nes", JobState.Completed, attempts = 1, totalElapsedMillis = 900L),
            QueueJob("saved", "saved.nes", "nes", JobState.Skipped, attempts = 1),
            QueueJob("running", "running.nes", "nes", JobState.Processing, attempts = 1),
            QueueJob(
                id = "waiting",
                fileName = "waiting.pbp",
                systemKey = "psx",
                state = JobState.Deferred,
                attempts = 1,
                deferReason = DeferReason.ProviderTimeout,
                nextEligibleAtMillis = 30_000L,
            ),
            QueueJob("queued", "queued.nes", "nes", JobState.Ready),
        ),
    )

    /** What [com.rork.rgdsartworkprep.domain.ScrapeCoordinator.cancel] does to the queue. */
    private fun cancel(queue: ScanQueue): List<String> =
        queue.finishPending(JobState.Skipped, "Scan cancelled")

    @Test
    fun `active work stops`() {
        val queue = midRunQueue()
        cancel(queue)
        assertEquals(JobState.Skipped, queue.job("running")?.state)
    }

    @Test
    fun `queued work stops`() {
        val queue = midRunQueue()
        cancel(queue)
        assertEquals(JobState.Skipped, queue.job("queued")?.state)
        assertNull("nothing may be offered after cancelling", queue.nextReady(Long.MAX_VALUE))
    }

    @Test
    fun `deferred retries stop`() {
        val queue = midRunQueue()
        cancel(queue)

        assertEquals(JobState.Skipped, queue.job("waiting")?.state)
        assertEquals(0, queue.deferredCount)
        // Even long after the backoff would have expired, nothing comes back.
        assertNull(queue.nextReady(Long.MAX_VALUE))
        assertNull(queue.nextEligibleAt())
    }

    @Test
    fun `completed artwork remains intact`() {
        val queue = midRunQueue()
        cancel(queue)

        val completed = queue.job("done")
        assertEquals(JobState.Completed, completed?.state)
        assertEquals(900L, completed?.totalElapsedMillis)
        assertEquals(1, queue.completedCount)
        // Work already skipped keeps its own outcome rather than being rewritten.
        assertEquals(JobState.Skipped, queue.job("saved")?.state)
    }

    @Test
    fun `cancelling reports what was still outstanding`() {
        val queue = midRunQueue()
        val stopped = cancel(queue)

        assertEquals(listOf("running", "waiting", "queued"), stopped)
        assertTrue(queue.isDrained)
    }

    @Test
    fun `cancelling twice changes nothing further`() {
        val queue = midRunQueue()
        cancel(queue)
        val second = cancel(queue)

        assertTrue("nothing was left to stop", second.isEmpty())
        assertEquals(1, queue.completedCount)
    }
}
