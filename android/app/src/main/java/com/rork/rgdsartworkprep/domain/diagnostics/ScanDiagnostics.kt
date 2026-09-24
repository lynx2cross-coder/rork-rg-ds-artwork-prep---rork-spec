package com.rork.rgdsartworkprep.domain.diagnostics

import com.rork.rgdsartworkprep.domain.queue.DeferReason
import com.rork.rgdsartworkprep.domain.queue.QueueJob
import com.rork.rgdsartworkprep.provider.ProviderCallObserver
import com.rork.rgdsartworkprep.provider.ProviderIndexTrace
import com.rork.rgdsartworkprep.provider.ProviderRequestObserver
import com.rork.rgdsartworkprep.provider.ProviderRequestTrace

/**
 * Records what happened during a scan, inside the app.
 *
 * Logcat was the previous answer and it did not survive: the bug report captured from
 * the RG DS contained none of the TIMING lines, because Android had already rotated
 * them away by the time the report was taken. Anything the user is expected to send
 * back therefore has to be held by the app itself.
 *
 * Recording is off unless the user switches it on, so an ordinary scan carries no
 * extra cost. The one exception is [romDeferred]: a game being set aside is the whole
 * reason the retry queue exists, so that is always recorded and is what lets the
 * queue screen explain itself without the user having enabled anything first.
 *
 * Implements [ProviderCallObserver] so provider timings arrive from the one place
 * every source call already passes through, with no source aware it is being timed,
 * and [ProviderRequestObserver] so the individual HTTP requests behind one such call
 * can be told apart.
 */
class ScanDiagnostics(
    private val log: DiagnosticLog = DiagnosticLog(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ProviderCallObserver, ProviderRequestObserver {

    /** Whether the user asked for the detailed per-provider record. */
    @Volatile
    var isDetailedEnabled: Boolean = false

    /**
     * The ROM currently being worked on, so provider events can be attributed without
     * threading an id through the provider interface.
     *
     * Safe because the scan processes exactly one ROM at a time — a property the queue
     * preserves deliberately, and which this relies on.
     */
    @Volatile
    private var currentRomId: String? = null

    val droppedCount: Int get() = log.droppedCount

    fun events(): List<DiagEvent> = log.snapshot()

    fun eventsFor(romId: String): List<DiagEvent> = log.eventsFor(romId)

    fun clear() {
        log.clear()
        currentRomId = null
    }

    /** Drops a ROM the user has ignored, so no line of the report still mentions it. */
    fun forgetRom(romId: String) {
        log.removeRom(romId)
        if (currentRomId == romId) currentRomId = null
    }

    fun scanStarted(discovered: Int, resumed: Boolean) {
        record(
            DiagEventType.ScanStart,
            fields = listOf(
                "discovered" to discovered.toString(),
                "resumed" to resumed.toString(),
            ),
        )
    }

    fun romDiscovered(romId: String, fileName: String, systemKey: String?) {
        if (!isDetailedEnabled) return
        record(
            DiagEventType.RomDiscovered,
            romId = romId,
            fields = listOf(
                "filename" to fileName,
                DiagnosticReport.FIELD_SYSTEM to (systemKey ?: "unknown"),
            ),
        )
    }

    /** Opens a ROM's section of the log and becomes the context for provider events. */
    fun romStarted(romId: String, fileName: String, systemKey: String?, attempt: Int) {
        currentRomId = romId
        val type = if (attempt > 1) DiagEventType.RetryStart else DiagEventType.RomStart
        record(
            type,
            romId = romId,
            fields = listOf(
                "filename" to fileName,
                DiagnosticReport.FIELD_SYSTEM to (systemKey ?: "unknown"),
                DiagnosticReport.FIELD_ATTEMPT to attempt.toString(),
            ),
        )
    }

    /**
     * Records that a match was obtained, and how long it took.
     *
     * Forced into the log even when detailed diagnostics are off, because this is the
     * line that distinguishes "the lookup timed out" from "the lookup succeeded and
     * the result was then discarded". Without it those two read identically in a
     * report, and the second one was misdiagnosed as the first.
     */
    fun romIdentified(romId: String, providerKey: String, elapsedMillis: Long) {
        record(
            DiagEventType.RomIdentified,
            romId = romId,
            elapsedMillis = elapsedMillis,
            fields = listOf(
                DiagnosticReport.FIELD_PROVIDER to providerKey,
                DiagnosticReport.FIELD_OUTCOME to "Identified",
            ),
            force = true,
        )
    }

    fun romFinished(romId: String, outcome: String, elapsedMillis: Long, attempt: Int) {
        val type = if (attempt > 1) DiagEventType.RetryResult else DiagEventType.RomResult
        record(
            type,
            romId = romId,
            elapsedMillis = elapsedMillis,
            fields = listOf(
                DiagnosticReport.FIELD_OUTCOME to outcome,
                DiagnosticReport.FIELD_ATTEMPT to attempt.toString(),
            ),
        )
        currentRomId = null
    }

    /**
     * Always recorded, enabled or not — see the class comment.
     */
    fun romDeferred(romId: String, reason: DeferReason, elapsedMillis: Long, detail: String?) {
        record(
            DiagEventType.RomDeferred,
            romId = romId,
            elapsedMillis = elapsedMillis,
            fields = buildList {
                add(DiagnosticReport.FIELD_REASON to reason.key)
                detail?.takeIf { it.isNotBlank() }?.let { add("detail" to it.take(MAX_DETAIL)) }
            },
            force = true,
        )
        currentRomId = null
    }

    fun scanComplete(completed: Int, skipped: Int, deferred: Int, failed: Int) {
        record(
            DiagEventType.ScanComplete,
            fields = listOf(
                "completed" to completed.toString(),
                "skipped" to skipped.toString(),
                "deferred" to deferred.toString(),
                "failed" to failed.toString(),
            ),
            force = true,
        )
    }

    fun scanCancelled(processed: Int, remaining: Int) {
        record(
            DiagEventType.ScanCancelled,
            fields = listOf(
                "processed" to processed.toString(),
                "remaining" to remaining.toString(),
            ),
            force = true,
        )
        currentRomId = null
    }

    // region ProviderCallObserver

    override fun onProviderCallStarted(providerKey: String, stage: String) {
        if (!isDetailedEnabled) return
        record(
            DiagEventType.ProviderStart,
            romId = currentRomId,
            fields = listOf(
                DiagnosticReport.FIELD_PROVIDER to providerKey,
                DiagnosticReport.FIELD_STAGE to stage,
            ),
        )
    }

    override fun onProviderCallFinished(
        providerKey: String,
        stage: String,
        elapsedMillis: Long,
        outcome: String,
    ) {
        if (!isDetailedEnabled) return
        record(
            DiagEventType.ProviderResult,
            romId = currentRomId,
            elapsedMillis = elapsedMillis,
            fields = listOf(
                DiagnosticReport.FIELD_PROVIDER to providerKey,
                DiagnosticReport.FIELD_STAGE to stage,
                DiagnosticReport.FIELD_OUTCOME to outcome,
            ),
        )
    }

    // endregion

    // region ProviderRequestObserver

    /**
     * Records one HTTP request.
     *
     * Deliberately gated on the detailed setting like every other per-call event: a
     * scan issues thousands of these and an ordinary run should carry no cost for
     * them. Byte counts and encodings are written as plain numbers and tokens, never
     * as a URL — the provider hands over a request *shape*, so no filename can arrive
     * here and bypass the report's own privacy setting.
     */
    override fun onProviderRequest(trace: ProviderRequestTrace) {
        if (!isDetailedEnabled) return
        record(
            DiagEventType.ProviderRequest,
            romId = currentRomId,
            elapsedMillis = trace.elapsedMillis,
            fields = buildList {
                add(DiagnosticReport.FIELD_PROVIDER to trace.providerKey)
                add(DiagnosticReport.FIELD_STAGE to trace.stage)
                add(DiagnosticReport.FIELD_ATTEMPT to trace.attempt.toString())
                add(DiagnosticReport.FIELD_METHOD to trace.method)
                add(DiagnosticReport.FIELD_TARGET to trace.target)
                add(DiagnosticReport.FIELD_STATUS to trace.status.toString())
                add(DiagnosticReport.FIELD_OUTCOME to trace.outcome)
                if (trace.wireBytes >= 0) {
                    add(DiagnosticReport.FIELD_WIRE_BYTES to trace.wireBytes.toString())
                }
                if (trace.declaredBytes >= 0) {
                    add(DiagnosticReport.FIELD_DECLARED_BYTES to trace.declaredBytes.toString())
                }
                if (trace.bodyBytes >= 0) {
                    add(DiagnosticReport.FIELD_BODY_BYTES to trace.bodyBytes.toString())
                }
                // Recorded even when absent: "the device did not ask for gzip" is a
                // finding, and an omitted field would read as "not measured".
                add(
                    DiagnosticReport.FIELD_ACCEPT_ENCODING to
                        (trace.acceptEncoding ?: NONE),
                )
                add(
                    DiagnosticReport.FIELD_CONTENT_ENCODING to
                        (trace.contentEncoding ?: NONE),
                )
            },
        )
    }

    /**
     * Records an index fetch. Forced into the log: whether the index was paid for is
     * the question the whole investigation turns on, and it happens a handful of times
     * per scan rather than thousands, so it costs nothing to always keep.
     */
    override fun onProviderIndex(trace: ProviderIndexTrace) {
        record(
            DiagEventType.ProviderIndex,
            romId = currentRomId,
            elapsedMillis = trace.elapsedMillis,
            fields = listOf(
                DiagnosticReport.FIELD_PROVIDER to trace.providerKey,
                DiagnosticReport.FIELD_FOLDER to trace.folder,
                DiagnosticReport.FIELD_ENTRIES to trace.entries.toString(),
                // The two halves of the elapsed time, so a slow index says which half
                // was slow. Without this the report states a total that no request in
                // it accounts for, which is exactly what stalled the investigation.
                DiagnosticReport.FIELD_FETCH_MILLIS to trace.fetchMillis.toString(),
                DiagnosticReport.FIELD_PARSE_MILLIS to trace.parseMillis.toString(),
                DiagnosticReport.FIELD_OUTCOME to trace.outcome,
            ),
            force = true,
        )
    }

    // endregion

    /** Per-ROM totals used by the report when the detailed log is unavailable. */
    fun summarise(jobs: List<QueueJob>): Map<String, Long> =
        jobs.associate { it.id to it.totalElapsedMillis }

    private fun record(
        type: DiagEventType,
        romId: String? = null,
        elapsedMillis: Long? = null,
        fields: List<Pair<String, String>> = emptyList(),
        force: Boolean = false,
    ) {
        if (!isDetailedEnabled && !force) return
        log.record(
            DiagEvent(
                type = type,
                atMillis = clock(),
                elapsedMillis = elapsedMillis,
                romId = romId,
                fields = fields,
            ),
        )
    }

    private companion object {
        const val MAX_DETAIL = 160

        /** Written when a header was absent, so that reads differently from unmeasured. */
        const val NONE = "none"
    }
}
