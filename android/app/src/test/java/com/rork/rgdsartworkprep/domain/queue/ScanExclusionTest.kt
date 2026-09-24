package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.data.IgnoredFiles
import com.rork.rgdsartworkprep.model.PrepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ignoring a file in a scan that already exists: one in progress, or one saved to
 * disk and resumed later.
 *
 * The rule is removal, not a verdict. An ignored file must not turn into a skip, an
 * unsupported row or an error — it must leave every count as if it had never been
 * found. The queue is the only thing that ever hands a file to the checksum, the
 * providers and the artwork download, so a job it no longer holds cannot reach them.
 */
class ScanExclusionTest {

    private fun job(id: String, name: String, state: JobState = JobState.Ready, attempts: Int = 0) =
        QueueJob(id = id, fileName = name, systemKey = "n3ds", state = state, attempts = attempts)

    private fun record(id: String, name: String, systemKey: String? = "n3ds") =
        RomRecord(documentId = id, uri = "content://$id", fileName = name, sizeBytes = 1L, systemKey = systemKey)

    // region the queue

    @Test
    fun `a removed job is never served again`() {
        val queue = ScanQueue(listOf(job("a", "boot11.bin"), job("b", "game.3ds")))
        queue.remove(listOf("a"))

        assertEquals("b", queue.nextReady(0L)?.id)
        queue.markProcessing("b", 0L)
        queue.markCompleted("b", 10L)
        assertNull("nothing left to process", queue.nextReady(Long.MAX_VALUE))
        assertNull(queue.job("a"))
    }

    @Test
    fun `a removed deferred job is not retried after its backoff`() {
        val queue = ScanQueue(listOf(job("a", "boot11.bin"), job("b", "game.3ds")))
        queue.markProcessing("a", 0L)
        queue.markDeferred("a", DeferReason.NetworkFailure, nowMillis = 0L, elapsedMillis = 5L)
        queue.remove(listOf("a"))

        assertNull(queue.nextEligibleAt())
        assertTrue(queue.retryableIds().isEmpty())
        assertTrue("no retry can revive it", queue.requeue(listOf("a")).isEmpty())
    }

    @Test
    fun `a removed job is in no count at all`() {
        val queue = ScanQueue(
            listOf(
                job("a", "boot11.bin", JobState.Skipped),
                job("b", "boot9.bin", JobState.Failed),
                job("c", "game.3ds", JobState.Completed),
            ),
        )
        queue.remove(listOf("a", "b"))

        assertEquals(1, queue.size)
        assertEquals(0, queue.skippedCount)
        assertEquals(0, queue.failedCount)
        assertEquals(1, queue.completedCount)
        assertEquals(1, queue.finishedCount)
        assertTrue(queue.isDrained)
    }

    @Test
    fun `removing ids that are not present changes nothing`() {
        val queue = ScanQueue(listOf(job("a", "game.3ds")))
        assertTrue(queue.remove(listOf("zzz")).isEmpty())
        assertEquals(1, queue.size)
    }

    /**
     * A job caught in flight when the loop is replaced did not fail, so it goes back
     * in line and gets its attempt back rather than moving toward the retry ceiling.
     */
    @Test
    fun `a job interrupted by the restart is put back without losing an attempt`() {
        val queue = ScanQueue(listOf(job("a", "game.3ds")))
        queue.markProcessing("a", 0L)
        assertEquals(1, queue.job("a")?.attempts)

        assertEquals(listOf("a"), queue.releaseProcessing())
        assertEquals(JobState.Ready, queue.job("a")?.state)
        assertEquals(0, queue.job("a")?.attempts)
    }

    @Test
    fun `release leaves finished and deferred jobs alone`() {
        val queue = ScanQueue(
            listOf(job("a", "x.3ds", JobState.Completed, 1), job("b", "y.3ds", JobState.Deferred, 1)),
        )
        assertTrue(queue.releaseProcessing().isEmpty())
        assertEquals(JobState.Completed, queue.job("a")?.state)
        assertEquals(JobState.Deferred, queue.job("b")?.state)
    }

    // endregion

    // region the run's rows and totals

    @Test
    fun `pruning removes the rows and shrinks the total`() {
        val pruned = ScanExclusion.prune(
            items = listOf("a", "b", "c"),
            jobs = listOf(job("a", "boot11.bin"), job("b", "g.3ds"), job("c", "h.3ds")),
            processed = 0,
            removedIds = setOf("a"),
            id = { it },
        )
        assertEquals(listOf("b", "c"), pruned.items)
        assertEquals(listOf("b", "c"), pruned.jobs.map { it.id })
        assertEquals(2, pruned.total)
    }

    /** A file that had already been settled no longer counts toward progress. */
    @Test
    fun `progress loses only the removed work that had finished`() {
        val pruned = ScanExclusion.prune(
            items = listOf("a", "b", "c", "d"),
            jobs = listOf(
                job("a", "boot11.bin", JobState.Skipped),
                job("b", "g.3ds", JobState.Completed),
                job("c", "boot9.bin", JobState.Ready),
                job("d", "h.3ds", JobState.Ready),
            ),
            processed = 2,
            removedIds = setOf("a", "c"),
            id = { it },
        )
        assertEquals(2, pruned.total)
        assertEquals("only b remains finished", 1, pruned.processed)
    }

    @Test
    fun `progress never exceeds the new total`() {
        val pruned = ScanExclusion.prune(
            items = listOf("a", "b"),
            jobs = listOf(job("a", "boot11.bin", JobState.Processing), job("b", "g.3ds", JobState.Completed)),
            processed = 5,
            removedIds = setOf("a"),
            id = { it },
        )
        assertEquals(1, pruned.total)
        assertTrue(pruned.processed <= pruned.total)
    }

    @Test
    fun `pruning nothing leaves the run exactly as it was`() {
        val jobs = listOf(job("a", "g.3ds", JobState.Completed))
        val pruned = ScanExclusion.prune(listOf("a"), jobs, 1, emptySet()) { it }
        assertEquals(listOf("a"), pruned.items)
        assertEquals(jobs, pruned.jobs)
        assertEquals(1, pruned.processed)
    }

    // endregion

    // region resuming a saved scan

    @Test
    fun `a saved scan loses its ignored files before anything is rebuilt`() {
        val snapshot = ScanSnapshot(
            scanId = "s",
            roms = listOf(record("a", "boot11.bin"), record("b", "game.3ds")),
            jobs = listOf(job("a", "boot11.bin"), job("b", "game.3ds")),
        )
        val filtered = ScanExclusion.filterSnapshot(snapshot, IgnoredFiles(listOf("BOOT11.BIN")))

        assertEquals(listOf("b"), filtered.roms.map { it.documentId })
        assertEquals(listOf("b"), filtered.jobs.map { it.id })
        assertEquals(1, filtered.discovered)
    }

    /** A run whose only pending work was ignored has nothing to pick back up. */
    @Test
    fun `a saved scan with only ignored work left is no longer resumable`() {
        val snapshot = ScanSnapshot(
            scanId = "s",
            roms = listOf(record("a", "boot11.bin"), record("b", "game.3ds")),
            jobs = listOf(job("a", "boot11.bin", JobState.Ready), job("b", "game.3ds", JobState.Completed)),
        )
        assertTrue(snapshot.isResumable)
        assertFalse(ScanExclusion.filterSnapshot(snapshot, IgnoredFiles(listOf("boot11.bin"))).isResumable)
    }

    @Test
    fun `a saved scan with nothing ignored is returned unchanged`() {
        val snapshot = ScanSnapshot(scanId = "s", roms = listOf(record("a", "game.3ds")), jobs = listOf(job("a", "game.3ds")))
        assertTrue(snapshot === ScanExclusion.filterSnapshot(snapshot, IgnoredFiles.NONE))
        assertTrue(snapshot === ScanExclusion.filterSnapshot(snapshot, IgnoredFiles(listOf("boot9.bin"))))
    }

    // endregion

    // region the system filter is a different thing and still works

    /**
     * A switched-off system is detected, listed and counted as skipped; an ignored
     * file is none of those. Both must keep behaving as they did.
     */
    @Test
    fun `a switched-off system still settles as a skip, exactly as in 1_4_0`() {
        val verdict = JobSettlement.verdictFor(
            status = PrepStatus.SystemDisabled,
            message = "Skipped",
            hint = null,
            elapsedMillis = 0L,
            budgetMillis = RetryPolicy.DEFAULT_JOB_BUDGET_MILLIS,
            budgetExpired = false,
        )
        assertTrue(verdict is JobVerdict.Skip)
    }

    @Test
    fun `ignoring files leaves the system filter untouched`() {
        val settings = AppSettings(disabledSystemKeys = setOf("psx"))
        IgnoredFiles(listOf("boot11.bin")).partition(listOf("boot11.bin")) { it }
        assertFalse(settings.isSystemEnabled("psx"))
        assertTrue(settings.isSystemEnabled("n3ds"))
    }

    // endregion
}
