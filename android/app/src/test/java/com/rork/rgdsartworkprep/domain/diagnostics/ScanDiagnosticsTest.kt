package com.rork.rgdsartworkprep.domain.diagnostics

import com.rork.rgdsartworkprep.domain.queue.DeferReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app recorder is what replaces logcat: the device bug report taken from the
 * RG DS had already lost every TIMING line, so anything the user is expected to send
 * back has to be held by the app itself.
 */
class ScanDiagnosticsTest {

    /** A fixed clock keeps assertions about ordering and stamps deterministic. */
    private class FakeClock(private var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(by: Long) {
            now += by
        }
    }

    private fun recorder(enabled: Boolean = true): Pair<ScanDiagnostics, FakeClock> {
        val clock = FakeClock()
        val diagnostics = ScanDiagnostics(DiagnosticLog(), clock)
        diagnostics.isDetailedEnabled = enabled
        return diagnostics to clock
    }

    @Test
    fun `provider timing is recorded against the rom being processed`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "Final Fantasy VII.pbp", "psx", attempt = 1)
        diagnostics.onProviderCallStarted("hasheous", "identify")
        diagnostics.onProviderCallFinished("hasheous", "identify", 13_021L, "NotFound")

        val result = diagnostics.eventsFor("rom1")
            .single { it.type == DiagEventType.ProviderResult }

        assertEquals("hasheous", result.field(DiagnosticReport.FIELD_PROVIDER))
        assertEquals("identify", result.field(DiagnosticReport.FIELD_STAGE))
        assertEquals("NotFound", result.field(DiagnosticReport.FIELD_OUTCOME))
        assertEquals(13_021L, result.elapsedMillis)
    }

    @Test
    fun `rom timing is recorded`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "Super Mario Bros.nes", "nes", attempt = 1)
        diagnostics.romFinished("rom1", "Downloaded", 12_400L, attempt = 1)

        val finished = diagnostics.eventsFor("rom1").single { it.type == DiagEventType.RomResult }
        assertEquals(12_400L, finished.elapsedMillis)
        assertEquals("Downloaded", finished.field(DiagnosticReport.FIELD_OUTCOME))
    }

    @Test
    fun `defer reason is recorded`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "slow.pbp", "psx", attempt = 1)
        diagnostics.romDeferred("rom1", DeferReason.ProviderTimeout, 93_100L, "ScreenScraper timed out")

        val deferred = diagnostics.eventsFor("rom1").single { it.type == DiagEventType.RomDeferred }
        assertEquals("provider_timeout", deferred.field(DiagnosticReport.FIELD_REASON))
        assertEquals(93_100L, deferred.elapsedMillis)
    }

    @Test
    fun `retry count is recorded and a second attempt is marked as a retry`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "slow.pbp", "psx", attempt = 2)
        diagnostics.romFinished("rom1", "Downloaded", 4_000L, attempt = 2)

        val events = diagnostics.eventsFor("rom1")
        val start = events.single { it.type == DiagEventType.RetryStart }
        val result = events.single { it.type == DiagEventType.RetryResult }

        assertEquals("2", start.field(DiagnosticReport.FIELD_ATTEMPT))
        assertEquals("2", result.field(DiagnosticReport.FIELD_ATTEMPT))
    }

    /**
     * Recording is opt-in so an ordinary scan pays nothing for it — but a game being
     * set aside is the reason the retry queue exists, so that is always kept.
     */
    @Test
    fun `detailed recording is off by default but deferrals are still kept`() {
        val (diagnostics, _) = recorder(enabled = false)

        diagnostics.romStarted("rom1", "slow.pbp", "psx", attempt = 1)
        diagnostics.onProviderCallFinished("hasheous", "identify", 10L, "NotFound")
        diagnostics.romDeferred("rom1", DeferReason.RateLimited, 500L, null)

        val events = diagnostics.events()
        assertTrue(events.none { it.type == DiagEventType.ProviderResult })
        assertTrue(events.none { it.type == DiagEventType.RomStart })
        assertNotNull(events.singleOrNull { it.type == DiagEventType.RomDeferred })
    }

    @Test
    fun `scan lifecycle events are always recorded`() {
        val (diagnostics, _) = recorder(enabled = false)
        diagnostics.scanStarted(discovered = 6, resumed = false)
        diagnostics.scanComplete(completed = 4, skipped = 1, deferred = 1, failed = 0)
        diagnostics.scanCancelled(processed = 5, remaining = 1)

        val types = diagnostics.events().map { it.type }
        assertTrue(types.contains(DiagEventType.ScanComplete))
        assertTrue(types.contains(DiagEventType.ScanCancelled))
    }

    @Test
    fun `timestamps advance with the clock`() {
        val (diagnostics, clock) = recorder()
        diagnostics.romStarted("rom1", "a.nes", "nes", attempt = 1)
        clock.advance(5_000L)
        diagnostics.romFinished("rom1", "Downloaded", 5_000L, attempt = 1)

        val events = diagnostics.eventsFor("rom1")
        assertEquals(1_000L, events.first().atMillis)
        assertEquals(6_000L, events.last().atMillis)
    }

    /** A handheld must not grow an unbounded log during a thousand-ROM scan. */
    @Test
    fun `the log is bounded and reports what it dropped`() {
        val log = DiagnosticLog(capacity = 10)
        val diagnostics = ScanDiagnostics(log) { 0L }
        diagnostics.isDetailedEnabled = true

        repeat(25) { index ->
            diagnostics.romStarted("rom$index", "game$index.nes", "nes", attempt = 1)
        }

        assertEquals(10, log.size())
        assertEquals(15, log.droppedCount)
    }

    @Test
    fun `clearing resets the log between scans`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "a.nes", "nes", attempt = 1)
        assertFalse(diagnostics.events().isEmpty())

        diagnostics.clear()

        assertTrue(diagnostics.events().isEmpty())
        assertEquals(0, diagnostics.droppedCount)
    }

    @Test
    fun `provider events after a rom finishes are not misattributed`() {
        val (diagnostics, _) = recorder()
        diagnostics.romStarted("rom1", "a.nes", "nes", attempt = 1)
        diagnostics.romFinished("rom1", "Downloaded", 100L, attempt = 1)
        // A late callback must not be filed against the game that already finished.
        diagnostics.onProviderCallFinished("hasheous", "identify", 50L, "Success")

        assertTrue(diagnostics.eventsFor("rom1").none { it.type == DiagEventType.ProviderResult })
    }
}
