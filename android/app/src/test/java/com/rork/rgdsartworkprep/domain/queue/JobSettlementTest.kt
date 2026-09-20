package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.model.PrepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the defect that made a working scan look broken.
 *
 * The previous build could not tell `processRom` returning "nothing fatal, carry on"
 * apart from the job budget expiring, because Kotlin flattens `ProviderError??` into
 * one nullable type. Every non-fatal ROM — including every success — was therefore
 * recorded as `Deferred / slow_response / "Exceeded the time budget"`, while the
 * artwork sat on disk. Two covers were downloaded and neither was reported.
 *
 * These assert the two rules that were violated: work that succeeded is recorded as
 * succeeding, and a defer reason has to be earned by evidence.
 */
class JobSettlementTest {

    private val budget = 240_000L

    private fun verdict(
        status: PrepStatus,
        message: String? = null,
        hint: DeferHint? = null,
        elapsedMillis: Long = 12_000L,
        budgetExpired: Boolean = false,
    ): JobVerdict = JobSettlement.verdictFor(
        status = status,
        message = message,
        hint = hint,
        elapsedMillis = elapsedMillis,
        budgetMillis = budget,
        budgetExpired = budgetExpired,
    )

    // region the reported regression

    /**
     * The Chrono Trigger / Sonic case: identify succeeded, the cover downloaded, and
     * the job was still reported as deferred with an API error on the row.
     */
    @Test
    fun `a downloaded cover settles as completed rather than deferred`() {
        val outcome = verdict(PrepStatus.Downloaded, message = "Saved")
        assertTrue("a saved cover must be Completed, got $outcome", outcome is JobVerdict.Complete)
    }

    @Test
    fun `an exported cover settles as completed`() {
        assertTrue(verdict(PrepStatus.Exported) is JobVerdict.Complete)
    }

    /**
     * The heart of the bug. A successful ROM is not fatal, so `processRom` returns
     * null — which must never on its own be read as the budget expiring.
     */
    @Test
    fun `success is never labelled slow response`() {
        val outcome = verdict(PrepStatus.Downloaded, elapsedMillis = 29_500L)
        assertTrue(outcome is JobVerdict.Complete)
        assertEquals(null, (outcome as? JobVerdict.Defer)?.reason)
    }

    /** Metroid Fusion: a confirmed miss was filed as "too slow". */
    @Test
    fun `not found settles as skipped and is not converted to slow response`() {
        val outcome = verdict(PrepStatus.NotFound, message = "No match")
        assertTrue("NotFound must be Skipped, got $outcome", outcome is JobVerdict.Skip)
    }

    /**
     * Panzer Dragoon: Saturn has scraping disabled, so the job made zero requests and
     * took 0.0 sec — and was still reported as having exceeded a four-minute budget.
     * A job that did no work can never be over budget.
     */
    @Test
    fun `an unsupported system with no work is never over budget`() {
        val outcome = verdict(
            status = PrepStatus.Unsupported,
            message = "Sega Saturn lookup is not enabled yet",
            elapsedMillis = 0L,
        )
        assertTrue("an unsupported system must be Skipped, got $outcome", outcome is JobVerdict.Skip)
    }

    /** Even asked to treat it as expired, zero elapsed time cannot be over budget. */
    @Test
    fun `zero elapsed time is never reported as exceeding the budget`() {
        val outcome = verdict(
            status = PrepStatus.Unsupported,
            elapsedMillis = 0L,
            budgetExpired = true,
        )
        assertTrue(outcome is JobVerdict.Skip)
    }

    @Test
    fun `already existing artwork keeps its skip behaviour`() {
        val outcome = verdict(PrepStatus.AlreadyExists, message = "Artwork already in place")
        assertTrue(outcome is JobVerdict.Skip)
        assertEquals("Artwork already in place", (outcome as JobVerdict.Skip).detail)
    }

    /**
     * A system switched off in Settings settles for good.
     *
     * It shares the Saturn case's shape — zero work done, so no clock may have an
     * opinion about it — and adds one of its own: the outcome was the user's
     * instruction, so deferring it would spend the retry queue on re-reading a setting
     * that has not changed.
     */
    @Test
    fun `a system switched off in settings settles as skipped`() {
        val outcome = verdict(
            status = PrepStatus.SystemDisabled,
            message = "Skipped \u2014 Sony PlayStation is switched off in Settings",
            elapsedMillis = 0L,
        )
        assertTrue("a switched-off system must be Skipped, got $outcome", outcome is JobVerdict.Skip)
        assertEquals(
            "Skipped \u2014 Sony PlayStation is switched off in Settings",
            (outcome as JobVerdict.Skip).detail,
        )
    }

    /** Even under an expired budget it stays a fact about the choice, not the moment. */
    @Test
    fun `a switched off system is never deferred as slow`() {
        val outcome = verdict(
            status = PrepStatus.SystemDisabled,
            elapsedMillis = 0L,
            budgetExpired = true,
        )
        assertTrue(outcome is JobVerdict.Skip)
    }

    /** Waiting on a person is not a failure and must not be retried behind them. */
    @Test
    fun `a pending user choice settles as skipped`() {
        assertTrue(verdict(PrepStatus.MultipleMatches) is JobVerdict.Skip)
    }

    /**
     * A row whose automatic match came back empty is waiting on a person too. It must
     * not be deferred: the queue would spend the whole budget re-running a lookup that
     * has already had its turn, and the row would still need the same tap at the end.
     */
    @Test
    fun `a row awaiting a manual choice settles as skipped`() {
        val outcome = verdict(
            status = PrepStatus.ChooseArtwork,
            message = "Automatic match unavailable \u00b7 tap to choose",
            elapsedMillis = 61_000L,
        )
        assertTrue("ChooseArtwork must be Skipped, got $outcome", outcome is JobVerdict.Skip)
    }

    /** Even with the clock against it, the row is still waiting on a person. */
    @Test
    fun `a row awaiting a manual choice is not converted to slow response`() {
        val outcome = verdict(
            status = PrepStatus.ChooseArtwork,
            elapsedMillis = 300_000L,
            budgetExpired = true,
        )
        assertTrue("got $outcome", outcome is JobVerdict.Skip)
    }

    // endregion

    // region the defer reason has to be earned

    @Test
    fun `slow response is only used when the elapsed time really exceeded the budget`() {
        val outcome = verdict(
            status = PrepStatus.ApiError,
            elapsedMillis = budget + 1_000L,
            budgetExpired = true,
        )
        assertEquals(DeferReason.SlowResponse, (outcome as JobVerdict.Defer).reason)
    }

    /** The report has to show the arithmetic, so a wrong label cannot survive reading. */
    @Test
    fun `an over budget detail quotes both numbers`() {
        val outcome = verdict(
            status = PrepStatus.ApiError,
            elapsedMillis = 250_000L,
            budgetExpired = true,
        ) as JobVerdict.Defer
        assertTrue("got: ${outcome.detail}", outcome.detail!!.contains("250"))
        assertTrue("got: ${outcome.detail}", outcome.detail.contains("240"))
    }

    /**
     * A job cut short while a service was failing was deferred for that failure. The
     * evidence the pipeline captured outranks the fact that the attempt was stopped.
     */
    @Test
    fun `recorded evidence outranks the budget when elapsed time is short`() {
        val outcome = verdict(
            status = PrepStatus.ApiError,
            hint = DeferHint(DeferReason.NetworkFailure, "Connection refused"),
            elapsedMillis = 4_500L,
            budgetExpired = true,
        )
        val defer = outcome as JobVerdict.Defer
        assertEquals(DeferReason.NetworkFailure, defer.reason)
        assertEquals("Connection refused", defer.detail)
    }

    @Test
    fun `each recorded reason is carried through untouched`() {
        val reasons = listOf(
            DeferReason.RateLimited,
            DeferReason.ServerFailure,
            DeferReason.ProviderTimeout,
            DeferReason.FileUnreadable,
            DeferReason.ArtworkIncomplete,
        )
        reasons.forEach { reason ->
            val outcome = verdict(
                status = PrepStatus.ApiError,
                hint = DeferHint(reason, "detail"),
            )
            assertEquals(reason, (outcome as JobVerdict.Defer).reason)
        }
    }

    /** An error with no transient evidence was permanent; retrying just repeats it. */
    @Test
    fun `an error with no evidence gives up rather than looping`() {
        val outcome = verdict(PrepStatus.ApiError, message = "Rejected")
        assertTrue("got $outcome", outcome is JobVerdict.GiveUp)
    }

    /**
     * A row still mid-flight reached no verdict, so there is nothing to hold against
     * the ROM — it is deferred, never failed.
     */
    @Test
    fun `a job stopped before any answer is deferred not failed`() {
        listOf(PrepStatus.Pending, PrepStatus.Working).forEach { status ->
            val outcome = verdict(status, elapsedMillis = 1_000L, budgetExpired = true)
            assertTrue("$status should defer, got $outcome", outcome is JobVerdict.Defer)
        }
    }

    // endregion

    // region queue behaviour preserved end to end

    /** A deferred job keeps its controlled retries, and a success is never re-fetched. */
    @Test
    fun `settlement drives the queue without disturbing existing retry behaviour`() {
        val queue = ScanQueue(
            listOf(
                QueueJob(id = "sonic", fileName = "Sonic the Hedgehog.md", systemKey = "megadrive"),
                QueueJob(id = "ff7", fileName = "Final Fantasy VII.cue", systemKey = "psx"),
            ),
            RetryPolicy(jobBudgetMillis = budget),
        )

        // Sonic downloads its cover.
        queue.markProcessing("sonic", 0L)
        val sonic = JobSettlement.verdictFor(
            status = PrepStatus.Downloaded,
            message = "Saved",
            hint = null,
            elapsedMillis = 36_700L,
            budgetMillis = budget,
            budgetExpired = false,
        )
        assertTrue(sonic is JobVerdict.Complete)
        queue.markCompleted("sonic", 36_700L)

        // FF7 genuinely runs past its budget and is set aside.
        queue.markProcessing("ff7", 40_000L)
        val ff7 = JobSettlement.verdictFor(
            status = PrepStatus.ApiError,
            message = null,
            hint = null,
            elapsedMillis = 723_100L,
            budgetMillis = budget,
            budgetExpired = true,
        ) as JobVerdict.Defer
        assertEquals(DeferReason.SlowResponse, ff7.reason)
        queue.markDeferred("ff7", ff7.reason, 763_100L, 723_100L, ff7.detail)

        assertEquals(1, queue.completedCount)
        assertEquals(1, queue.deferredCount)

        // The completed cover is never offered again, and the deferred one still gets
        // its controlled retry once its backoff expires.
        assertEquals(null, queue.nextReady(763_100L))
        assertEquals("ff7", queue.nextReady(763_100L + 10_000L)?.id)
        assertEquals(listOf("ff7"), queue.retryableIds())
    }

    /** A completed job stays completed even if a manual retry sweeps over it. */
    @Test
    fun `completed work is never re-fetched`() {
        val queue = ScanQueue(
            listOf(QueueJob(id = "sonic", fileName = "Sonic the Hedgehog.md", systemKey = "megadrive")),
        )
        queue.markProcessing("sonic", 0L)
        queue.markCompleted("sonic", 1_000L)

        queue.requeue(listOf("sonic"))

        assertEquals(JobState.Completed, queue.job("sonic")?.state)
        assertEquals(null, queue.nextReady(2_000L))
    }

    // endregion
}
