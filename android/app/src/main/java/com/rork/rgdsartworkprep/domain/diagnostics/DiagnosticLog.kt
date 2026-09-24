package com.rork.rgdsartworkprep.domain.diagnostics

import kotlinx.serialization.Serializable

/**
 * The kinds of thing worth writing down during a scan.
 *
 * Note that PROVIDER ELAPSED is not a separate entry: the elapsed time is carried on
 * [ProviderResult] itself. Emitting the same number twice would double the size of
 * the log without answering a single extra question.
 */
@Serializable
enum class DiagEventType(val label: String) {
    ScanStart("SCAN START"),
    RomDiscovered("ROM DISCOVERED"),
    RomStart("ROM START"),
    ProviderStart("PROVIDER START"),
    ProviderResult("PROVIDER RESULT"),

    /**
     * One HTTP request, with its own timing, method, status and byte counts.
     *
     * A source call is not a request: a libretro identify can issue up to twelve of
     * them, and [ProviderResult] reports only their sum. That sum cannot say which
     * request was slow, how many were retries of the same one, or how many bytes a
     * yes/no answer cost — all of which the report is expected to answer.
     */
    ProviderRequest("REQUEST"),

    /**
     * A system index was fetched and parsed.
     *
     * Whether the index was paid for at all is a per-system fact, not a per-request
     * one: it is fetched once and reused for every later ROM, so the ROM that happens
     * to pay for it looks anomalously slow while the rest look free.
     */
    ProviderIndex("INDEX"),

    /**
     * A match was obtained, with the time it took to get there.
     *
     * Recorded separately from [RomResult] because the two answer different
     * questions, and a report that could not separate them was misread: a ROM whose
     * lookup succeeded slowly and was then discarded showed a long elapsed time next
     * to a deferral, which reads as "it timed out" when in fact the app had the right
     * answer and threw it away. This makes the moment the match arrived explicit.
     */
    RomIdentified("ROM IDENTIFIED"),
    RomResult("ROM RESULT"),
    RomDeferred("ROM DEFERRED"),
    RetryStart("RETRY START"),
    RetryResult("RETRY RESULT"),
    ScanComplete("SCAN COMPLETE"),
    ScanCancelled("SCAN CANCELLED"),
}

/**
 * One line of the scan's own history.
 *
 * [fields] is an ordered list of name/value pairs rather than a free-form sentence,
 * so the report can be grouped and filtered rather than only read. Values are
 * written by the app itself and never carry credentials — see [DiagnosticReport] for
 * the guarantee that enforces it.
 */
@Serializable
data class DiagEvent(
    val type: DiagEventType,
    /** Wall-clock stamp, used for the `11:02:14` column in the report. */
    val atMillis: Long,
    /** Monotonic duration of whatever this event concludes, when it concludes one. */
    val elapsedMillis: Long? = null,
    /** The ROM this belongs to, so the report can group by game. Null for scan-wide events. */
    val romId: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
) {
    fun field(name: String): String? = fields.firstOrNull { it.first == name }?.second
}

/**
 * A bounded, in-memory history of the current scan.
 *
 * Deliberately pure and synchronous so the queue can be exercised on the JVM. The
 * buffer is capped because a thousand-ROM scan would otherwise grow without limit on
 * a handheld: once full, the oldest entries are dropped and counted, so a truncated
 * report says so instead of silently lying about what happened.
 */
class DiagnosticLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val events = ArrayDeque<DiagEvent>()

    /** Entries dropped because the buffer was full. */
    var droppedCount: Int = 0
        private set

    @Synchronized
    fun record(event: DiagEvent) {
        events.addLast(event)
        while (events.size > capacity) {
            events.removeFirst()
            droppedCount++
        }
    }

    @Synchronized
    fun snapshot(): List<DiagEvent> = events.toList()

    @Synchronized
    fun eventsFor(romId: String): List<DiagEvent> = events.filter { it.romId == romId }

    @Synchronized
    fun clear() {
        events.clear()
        droppedCount = 0
    }

    /**
     * Forgets everything recorded for one ROM.
     *
     * Used when the user ignores a file: an ignored file is meant to leave no trace
     * outside the ignore list itself, and that includes the report they might share.
     */
    @Synchronized
    fun removeRom(romId: String) {
        events.removeAll { it.romId == romId }
    }

    @Synchronized
    fun size(): Int = events.size

    companion object {
        /**
         * Roughly a 250-ROM scan at four sources each, which covers the whole test
         * library several times over while staying small enough to hold in memory and
         * share as text.
         *
         * Raised when per-request events were added: one libretro identify can now
         * emit up to twelve entries of its own, so the previous ceiling would have
         * started dropping the beginning of a run — and a report that silently loses
         * its first ROMs is worse than one that is merely large.
         */
        const val DEFAULT_CAPACITY = 12_000
    }
}
