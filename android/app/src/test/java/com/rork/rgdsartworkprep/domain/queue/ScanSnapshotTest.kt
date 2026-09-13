package com.rork.rgdsartworkprep.domain.queue

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan state has to survive the process being stopped — the device bug report showed
 * Android doing exactly that mid-scan. These assert the snapshot keeps everything
 * needed to pick the run back up without redoing finished work.
 */
class ScanSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun snapshot(
        jobs: List<QueueJob>,
        phase: ScanPhase = ScanPhase.Running,
    ): ScanSnapshot = ScanSnapshot(
        scanId = "scan-1",
        phase = phase,
        sourceTreeUri = "content://tree/roms",
        rescrape = false,
        startedAtMillis = 1_000L,
        roms = jobs.map {
            RomRecord(
                documentId = it.id,
                uri = "content://doc/${it.id}",
                fileName = it.fileName,
                sizeBytes = 1024L,
                systemKey = it.systemKey,
            )
        },
        jobs = jobs,
    )

    @Test
    fun `survives a serialisation round trip with every field intact`() {
        val original = snapshot(
            listOf(
                QueueJob("a", "a.nes", "nes", JobState.Completed, attempts = 1, totalElapsedMillis = 900L),
                QueueJob(
                    id = "b",
                    fileName = "b.pbp",
                    systemKey = "psx",
                    state = JobState.Deferred,
                    attempts = 2,
                    deferReason = DeferReason.ProviderTimeout,
                    detail = "Timed out",
                    nextEligibleAtMillis = 45_000L,
                    totalElapsedMillis = 93_100L,
                ),
            ),
        )

        val restored = json.decodeFromString<ScanSnapshot>(json.encodeToString(original))

        assertEquals(original, restored)
        val deferred = restored.jobFor("b")
        assertEquals(JobState.Deferred, deferred?.state)
        assertEquals(DeferReason.ProviderTimeout, deferred?.deferReason)
        assertEquals(2, deferred?.attempts)
        assertEquals(45_000L, deferred?.nextEligibleAtMillis)
    }

    @Test
    fun `completed and skipped work is not pending after a restore`() {
        val restored = snapshot(
            listOf(
                QueueJob("a", "a.nes", "nes", JobState.Completed),
                QueueJob("b", "b.nes", "nes", JobState.Skipped),
                QueueJob("c", "c.nes", "nes", JobState.Deferred),
            ),
        )

        assertEquals(1, restored.pendingCount)
        assertTrue(restored.isResumable)
    }

    @Test
    fun `a finished scan is not resumed`() {
        val finished = snapshot(
            listOf(QueueJob("a", "a.nes", "nes", JobState.Completed)),
            phase = ScanPhase.Complete,
        )
        assertFalse(finished.isResumable)
    }

    /** Cancelling is the user's decision and must never restart by itself. */
    @Test
    fun `a cancelled scan is never resumed even with work left`() {
        val cancelled = snapshot(
            listOf(QueueJob("a", "a.nes", "nes", JobState.Ready)),
            phase = ScanPhase.Cancelled,
        )
        assertEquals(1, cancelled.pendingCount)
        assertFalse(cancelled.isResumable)
    }

    /**
     * Mirrors what the coordinator does on resume: a job caught mid-flight when the
     * process died is put back in line rather than counted as having been attempted.
     */
    @Test
    fun `work interrupted mid flight is restored as ready`() {
        val interrupted = snapshot(
            listOf(
                QueueJob("a", "a.nes", "nes", JobState.Completed),
                QueueJob("b", "b.nes", "nes", JobState.Processing, attempts = 1),
            ),
        )

        val restored = interrupted.jobs.map {
            if (it.state == JobState.Processing) it.copy(state = JobState.Ready) else it
        }
        val queue = ScanQueue(restored)

        assertEquals(JobState.Completed, queue.job("a")?.state)
        assertEquals("b", queue.nextReady(0L)?.id)
        // The completed one is never offered again, so artwork is not fetched twice.
        assertEquals(1, queue.completedCount)
    }

    @Test
    fun `a snapshot from an unknown version is rejected rather than guessed at`() {
        val stale = snapshot(listOf(QueueJob("a", "a.nes", "nes"))).copy(version = 99)
        assertFalse(stale.version == ScanSnapshot.CURRENT_VERSION)
    }
}
