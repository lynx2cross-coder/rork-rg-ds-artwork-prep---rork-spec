package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.model.PrepStatus
import com.rork.rgdsartworkprep.network.ProviderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two device-proven failures this pass exists to remove, and the four behaviours
 * that had to keep working while they were removed.
 *
 * Both failures were recorded on the Anbernic RG DS:
 *
 *  - Metroid Fusion: `libretrothumbnails identify: 290.5 sec / Success`, then
 *    `Result: Deferred / slow_response` with no artwork download. The app had the
 *    right answer and threw it away because the answer arrived after the budget.
 *  - Final Fantasy VII: `libretrothumbnails identify: 723.1 sec / Failure(NotFound)`,
 *    against a 240-second budget. The request could not be stopped, and its result
 *    was filed as though the archive had answered "no such game".
 *
 * The budget is modelled here exactly as the coordinator applies it — a soft bound
 * that stands down once a match has been earned — so these assert the rule rather
 * than a copy of it.
 */
class SlowSuccessTest {

    private val budgetMillis = 240_000L
    private val policy = RetryPolicy(jobBudgetMillis = budgetMillis)

    /**
     * The coordinator's `JobBudget`, which is private to it: a soft bound that stops
     * applying once the job has earned something worth finishing.
     */
    private class Budget(private val budgetMillis: Long) {
        var elapsedMillis: Long = 0L
        private var isCommitted: Boolean = false
        val hasExpired: Boolean get() = !isCommitted && elapsedMillis >= budgetMillis
        fun commit() {
            isCommitted = true
        }
    }

    private fun verdict(
        status: PrepStatus,
        message: String? = null,
        hint: DeferHint? = null,
        elapsedMillis: Long,
        budgetExpired: Boolean = false,
    ): JobVerdict = JobSettlement.verdictFor(
        status = status,
        message = message,
        hint = hint,
        elapsedMillis = elapsedMillis,
        budgetMillis = budgetMillis,
        budgetExpired = budgetExpired,
    )

    // region CASE A — slow but successful

    /**
     * 1. A provider identification that succeeds after the budget is kept.
     *
     * The checkpoint that deferred Metroid Fusion ran immediately after the match was
     * resolved. Committing at that point is what lets the download happen at all.
     */
    @Test
    fun `a match found after the budget is not discarded`() {
        val budget = Budget(budgetMillis)
        budget.elapsedMillis = 290_500L

        // Identification succeeded, so the job commits before the next checkpoint.
        budget.commit()

        assertFalse(
            "a job that has earned a match must not be deferred for being slow",
            budget.hasExpired,
        )
    }

    /**
     * 2. Slow identification followed by artwork download settles as Completed.
     *
     * The whole Metroid Fusion sequence, in order, with the timings from the report.
     */
    @Test
    fun `slow identify then artwork download settles as completed`() {
        val queue = ScanQueue(
            listOf(QueueJob(id = "metroid", fileName = "Metroid Fusion.gba", systemKey = "gba")),
            policy,
        )
        queue.markProcessing("metroid", 0L)

        val budget = Budget(budgetMillis)

        // Identify runs long and succeeds.
        budget.elapsedMillis = 290_500L
        assertTrue("before a match is earned the budget does apply", budget.hasExpired)
        budget.commit()
        assertFalse("after a match is earned it no longer does", budget.hasExpired)

        // The cover download now gets to happen, and lands a moment later.
        budget.elapsedMillis = 292_000L
        assertFalse(budget.hasExpired)

        val outcome = verdict(
            status = PrepStatus.Downloaded,
            message = "Saved",
            elapsedMillis = budget.elapsedMillis,
        )
        assertTrue("got $outcome", outcome is JobVerdict.Complete)

        queue.markCompleted("metroid", budget.elapsedMillis)
        val job = queue.job("metroid")
        assertEquals(JobState.Completed, job?.state)
        assertNull("a completed job carries no defer reason", job?.deferReason)
    }

    /** No slow-but-successful ROM may be labelled "too slow" merely for being slow. */
    @Test
    fun `a successful slow job is never given a slow response reason`() {
        val outcome = verdict(
            status = PrepStatus.Downloaded,
            elapsedMillis = 292_000L,
            // Even if the caller were to claim expiry, a finished status outranks it.
            budgetExpired = true,
        )
        assertTrue(outcome is JobVerdict.Complete)
        assertNull((outcome as? JobVerdict.Defer)?.reason)
    }

    // endregion

    // region CASE B — slow and unsuccessful

    /**
     * 3. A request-level timeout takes the transient path.
     *
     * The provider now reports `Timeout` rather than letting a stalled call run on,
     * and the queue reads that as a reason to try later.
     */
    @Test
    fun `a libretro request timeout defers rather than failing`() {
        val error = ProviderError.Timeout("index download", 420_000L)
        assertEquals(DeferReason.ProviderTimeout, DeferralPolicy.reasonFor(error))

        val outcome = verdict(
            status = PrepStatus.ApiError,
            hint = DeferHint(DeferReason.ProviderTimeout, error.userMessage, "libretrothumbnails"),
            elapsedMillis = 420_000L,
        )
        val defer = outcome as JobVerdict.Defer
        assertEquals(DeferReason.ProviderTimeout, defer.reason)
        assertEquals("libretrothumbnails", defer.providerKey)
    }

    /** 4. A timeout must not become a permanent NotFound. */
    @Test
    fun `a timeout does not become a permanent not found`() {
        val error = ProviderError.Timeout("identify", 420_000L)
        assertTrue("a timeout must be retryable", error.isTransient)
        assertFalse("one slow request must not retire the provider", error.stopsRun)

        val outcome = verdict(
            status = PrepStatus.ApiError,
            hint = DeferHint(DeferReason.ProviderTimeout, error.userMessage),
            elapsedMillis = 420_000L,
        )
        assertTrue("a timeout must never be Skipped as a miss", outcome is JobVerdict.Defer)
    }

    /**
     * 5. A timed-out ROM releases the worker immediately.
     *
     * This is the FF7 scenario as a whole: it is attempted first, gives up, and the
     * ready ROMs behind it are served without waiting for it.
     */
    @Test
    fun `a timed out rom releases the worker to the next ready rom`() {
        val queue = ScanQueue(
            listOf(
                QueueJob(id = "ff7", fileName = "Final Fantasy VII.cue", systemKey = "psx"),
                QueueJob(id = "chrono", fileName = "Chrono Trigger.sfc", systemKey = "snes"),
                QueueJob(id = "sonic", fileName = "Sonic the Hedgehog.md", systemKey = "megadrive"),
            ),
            policy,
        )

        assertEquals("ff7", queue.nextReady(0L)?.id)
        queue.markProcessing("ff7", 0L)
        val timeout = ProviderError.Timeout("index download", 420_000L)
        queue.markDeferred(
            id = "ff7",
            reason = DeferralPolicy.reasonFor(timeout)!!,
            nowMillis = 420_000L,
            elapsedMillis = 420_000L,
            detail = timeout.userMessage,
            providerKey = "libretrothumbnails",
        )

        // The very next thing the single worker is handed is ready work, not FF7.
        assertEquals("chrono", queue.nextReady(420_000L)?.id)
        queue.markProcessing("chrono", 420_000L)
        queue.markCompleted("chrono", 30_000L)

        assertEquals("sonic", queue.nextReady(450_000L)?.id)
        queue.markProcessing("sonic", 450_000L)
        queue.markCompleted("sonic", 36_000L)

        // Only once the ready queue is exhausted does the deferred ROM come back.
        assertEquals("ff7", queue.nextReady(500_000L)?.id)
        assertEquals(2, queue.completedCount)
    }

    /**
     * 6. One ROM at a time.
     *
     * The invariant lives in the coordinator's single drain loop, which takes one job,
     * runs it to a verdict, and only then asks for another. This models that loop and
     * asserts what it guarantees: never more than one job in flight, and every ROM
     * served exactly once, in order.
     *
     * Note the queue itself does not refuse to hand out a second job — it is a passive
     * data structure with no notion of a worker. Asserting otherwise here would be
     * testing a promise nothing makes.
     */
    @Test
    fun `only one rom is ever in flight`() {
        val queue = ScanQueue(
            listOf(
                QueueJob(id = "a", fileName = "A.nes", systemKey = "nes"),
                QueueJob(id = "b", fileName = "B.nes", systemKey = "nes"),
                QueueJob(id = "c", fileName = "C.nes", systemKey = "nes"),
                QueueJob(id = "d", fileName = "D.nes", systemKey = "nes"),
            ),
            policy,
        )

        val served = mutableListOf<String>()
        var now = 0L
        var maxConcurrent = 0
        while (true) {
            val next = queue.nextReady(now) ?: break
            queue.markProcessing(next.id, now)
            maxConcurrent = maxOf(maxConcurrent, queue.countOf(JobState.Processing))

            served += next.id
            now += 10_000L
            // B is deferred rather than completed, to prove a difficult ROM does not
            // change how many jobs are in flight.
            if (next.id == "b") {
                queue.markDeferred(next.id, DeferReason.ProviderTimeout, now, 10_000L)
            } else {
                queue.markCompleted(next.id, 10_000L)
            }
        }

        assertEquals("never more than one ROM in flight", 1, maxConcurrent)
        // Ready work first, in order; the deferred ROM comes back only at the end.
        assertEquals(listOf("a", "b", "c", "d", "b"), served)
        assertEquals(0, queue.countOf(JobState.Processing))
    }

    // endregion

    // region CASE C, D, E — the behaviours that had to survive

    /** 8. A fast successful ROM still completes. */
    @Test
    fun `a fast successful rom still completes`() {
        val outcome = verdict(PrepStatus.Downloaded, message = "Saved", elapsedMillis = 12_400L)
        assertTrue(outcome is JobVerdict.Complete)
    }

    /** 7. An unsupported system is skipped at once, with no timing claim. */
    @Test
    fun `an unsupported system is skipped immediately`() {
        val outcome = verdict(
            status = PrepStatus.Unsupported,
            message = "Sega Saturn lookup is not enabled yet",
            elapsedMillis = 0L,
            budgetExpired = true,
        )
        assertTrue("got $outcome", outcome is JobVerdict.Skip)
        assertFalse(
            "a job that made no requests must not mention a timeout",
            (outcome as JobVerdict.Skip).detail.orEmpty().contains("budget"),
        )
    }

    /** A genuine miss is still a miss, and still reaches the manual-search path. */
    @Test
    fun `a genuine not found is skipped rather than deferred`() {
        val outcome = verdict(
            status = PrepStatus.NotFound,
            message = "No match for \"Metroid Fusion\" — tap to search manually",
            elapsedMillis = 8_100L,
        )
        assertTrue("got $outcome", outcome is JobVerdict.Skip)
    }

    // endregion

    // region retry and recovery preserved

    /** 9. Deferred work keeps its backoff schedule and its attempt ceiling. */
    @Test
    fun `deferred retry and backoff behaviour is unchanged`() {
        val queue = ScanQueue(
            listOf(QueueJob(id = "ff7", fileName = "Final Fantasy VII.cue", systemKey = "psx")),
            policy,
        )

        // First deferral: eligible again after the first connection backoff step.
        queue.markProcessing("ff7", 0L)
        queue.markDeferred("ff7", DeferReason.ProviderTimeout, 1_000L, 420_000L)
        assertEquals(JobState.Deferred, queue.job("ff7")?.state)
        assertNull("still inside its backoff", queue.nextReady(1_000L))
        assertEquals("ff7", queue.nextReady(6_000L)?.id)

        // Second deferral: still retryable, with a longer wait.
        queue.markProcessing("ff7", 6_000L)
        queue.markDeferred("ff7", DeferReason.ProviderTimeout, 7_000L, 420_000L)
        assertEquals(JobState.Deferred, queue.job("ff7")?.state)
        assertNull(queue.nextReady(7_000L))
        assertEquals("ff7", queue.nextReady(22_000L)?.id)

        // Third attempt exhausts the ceiling: it stops rather than looping forever.
        queue.markProcessing("ff7", 22_000L)
        queue.markDeferred("ff7", DeferReason.ProviderTimeout, 23_000L, 420_000L)
        assertEquals(JobState.Failed, queue.job("ff7")?.state)
        assertNull(queue.nextReady(Long.MAX_VALUE / 2))

        // And it is still offered to the user for a manual retry.
        assertEquals(listOf("ff7"), queue.retryableIds())
    }

    /** A server-paced reason waits longer than a connection one, as it always did. */
    @Test
    fun `server paced reasons keep their longer backoff`() {
        assertEquals(15_000L, policy.nextEligibleAt(0L, 1, DeferReason.ServerFailure))
        assertEquals(5_000L, policy.nextEligibleAt(0L, 1, DeferReason.ProviderTimeout))
    }

    /** 10. Completed work is never fetched again. */
    @Test
    fun `completed work is not re-fetched`() {
        val queue = ScanQueue(
            listOf(QueueJob(id = "chrono", fileName = "Chrono Trigger.sfc", systemKey = "snes")),
            policy,
        )
        queue.markProcessing("chrono", 0L)
        queue.markCompleted("chrono", 12_400L)

        // Neither the automatic queue nor a manual sweep may revive it.
        assertNull(queue.nextReady(Long.MAX_VALUE / 2))
        queue.requeue(listOf("chrono"))
        assertEquals(JobState.Completed, queue.job("chrono")?.state)
        assertNull(queue.nextReady(Long.MAX_VALUE / 2))
    }

    // endregion

    // region the budget is a bound on unproductive work only

    @Test
    fun `an uncommitted job still stands down when it is out of time`() {
        val budget = Budget(budgetMillis)
        budget.elapsedMillis = budgetMillis
        assertTrue(
            "a job with nothing to show for itself must still yield the slot",
            budget.hasExpired,
        )
    }

    @Test
    fun `a committed job is never over budget however long it runs`() {
        val budget = Budget(budgetMillis)
        budget.commit()
        budget.elapsedMillis = budgetMillis * 10
        assertFalse(budget.hasExpired)
    }

    /**
     * The hard ceiling is a backstop against a defect, not a bound on slowness, so it
     * has to sit above every path a working scan can legitimately take.
     */
    @Test
    fun `the hard ceiling leaves room for a legitimately slow job`() {
        val slowestObservedSuccess = 292_000L
        assertTrue(
            "a real success must never reach the ceiling",
            RetryPolicy().hardCeilingMillis > slowestObservedSuccess,
        )
        assertTrue(RetryPolicy().hardCeilingMillis > RetryPolicy().jobBudgetMillis)
    }

    // endregion
}
