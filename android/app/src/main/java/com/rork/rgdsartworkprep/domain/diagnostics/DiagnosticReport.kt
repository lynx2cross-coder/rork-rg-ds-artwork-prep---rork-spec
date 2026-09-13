package com.rork.rgdsartworkprep.domain.diagnostics

import com.rork.rgdsartworkprep.domain.queue.DeferReason
import com.rork.rgdsartworkprep.domain.queue.JobState
import com.rork.rgdsartworkprep.domain.queue.QueueJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything about the build and device that a report needs to be actionable. */
data class ReportEnvironment(
    val appName: String,
    val versionName: String,
    /** The version this build's source declared. */
    val versionCode: Int,
    /**
     * The version the packaged app actually carries.
     *
     * Normally identical to [versionCode]. It is recorded separately because a device
     * report arrived stamped with a Unix timestamp where the source said 14: the
     * packaging step rewrites the manifest, and a silent disagreement in the field
     * that identifies a build is worse than a visible one.
     */
    val packagedVersionCode: Int = versionCode,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    /** Source names that were switched on, e.g. `hasheous, libretrothumbnails`. */
    val enabledProviders: List<String>,
    val generatesGamelist: Boolean,
    val writesToLibrary: Boolean,
)

/** Headline numbers for the scan the report describes. */
data class ReportSummary(
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
    val discovered: Int,
    val completed: Int,
    val skipped: Int,
    val deferred: Int,
    val failed: Int,
    val outcome: String,
)

/**
 * Turns a scan's history into text a person can read and send.
 *
 * The point of the report is to answer one question — "why did this particular ROM
 * take eight minutes?" — so it is organised per ROM, with each source's own elapsed
 * time listed beneath it. A totals-only summary cannot answer that question, and a
 * raw event dump makes the reader find the answer themselves.
 *
 * ## What is deliberately excluded
 *
 * ROM contents, artwork bytes, API keys, passwords, tokens, SAF tree URIs and
 * Android system logs never reach this file. The report is built solely from
 * [DiagEvent]s the app wrote itself, and those only ever carry provider names,
 * stages, outcomes and durations. [assertNoSecrets] re-checks the finished text as a
 * backstop so a future field cannot quietly leak one.
 */
object DiagnosticReport {

    /**
     * @param includeFileNames when false, every ROM is listed as `Game 1`, `Game 2`
     *   and so on. Off by default: a filename is the one genuinely personal thing in
     *   a report, and the timing question can be answered without it.
     */
    fun build(
        environment: ReportEnvironment,
        summary: ReportSummary,
        jobs: List<QueueJob>,
        events: List<DiagEvent>,
        includeFileNames: Boolean,
        droppedEvents: Int = 0,
    ): String {
        val labels = labelsFor(jobs, includeFileNames)
        val text = buildString {
            appendHeader(environment)
            appendSummary(summary, droppedEvents)
            appendPerRom(jobs, events, labels)
            appendDeferredGroups(jobs, labels)
            appendLine()
            appendLine(
                "No ROM contents, artwork, credentials or system logs are included in this report.",
            )
            if (!includeFileNames) {
                appendLine(
                    "Filenames were withheld. Turn on \"Include ROM filenames\" in Diagnostics " +
                        "to name the games above.",
                )
            }
        }
        return redact(text)
    }

    private fun StringBuilder.appendHeader(environment: ReportEnvironment) {
        appendLine("${environment.appName} — scan diagnostic report")
        appendLine("Generated: ${stamp(System.currentTimeMillis())}")
        appendLine()
        appendLine("APP INFORMATION")
        appendLine("  Version: ${environment.versionName} (build ${environment.versionCode})")
        // Only when they disagree: on an ordinary build this line would be noise, but
        // when the packaged value was rewritten it is the difference between a report
        // that identifies its build and one that does not.
        if (environment.packagedVersionCode != environment.versionCode) {
            appendLine("  Packaged build: ${environment.packagedVersionCode} (rewritten at packaging)")
        }
        appendLine("  Scan engine: $SCAN_ENGINE_REVISION")
        appendLine("  Android: ${environment.androidRelease} (API ${environment.sdkInt})")
        appendLine("  Device: ${environment.manufacturer} ${environment.model}")
        val sources = environment.enabledProviders.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: "none"
        appendLine("  Sources enabled: $sources")
        appendLine("  Writes into library: ${yesNo(environment.writesToLibrary)}")
        appendLine("  Generates gamelist.xml: ${yesNo(environment.generatesGamelist)}")
        appendLine()
    }

    private fun StringBuilder.appendSummary(summary: ReportSummary, droppedEvents: Int) {
        appendLine("SCAN INFORMATION")
        appendLine("  Started: ${summary.startedAtMillis?.let(::stamp) ?: "—"}")
        appendLine("  Ended: ${summary.endedAtMillis?.let(::stamp) ?: "still running"}")
        appendLine("  Duration: ${durationOf(summary.startedAtMillis, summary.endedAtMillis)}")
        appendLine("  Outcome: ${summary.outcome}")
        appendLine("  Discovered: ${summary.discovered}")
        appendLine("  Completed: ${summary.completed}")
        appendLine("  Skipped: ${summary.skipped}")
        appendLine("  Queued for retry: ${summary.deferred}")
        appendLine("  Gave up: ${summary.failed}")
        if (droppedEvents > 0) {
            appendLine("  Note: $droppedEvents older log entries were dropped (buffer full).")
        }
        appendLine()
    }

    /**
     * The part that actually answers "why was this slow": every source tried for a
     * ROM, in order, with its own elapsed time beside its outcome.
     */
    private fun StringBuilder.appendPerRom(
        jobs: List<QueueJob>,
        events: List<DiagEvent>,
        labels: Map<String, String>,
    ) {
        appendLine("PER-ROM INFORMATION")
        if (jobs.isEmpty()) {
            appendLine("  No ROMs were processed.")
            appendLine()
            return
        }
        val byRom = events.filter { it.romId != null }.groupBy { it.romId }
        // Slowest first: the reason for the report is almost always at the top.
        jobs.sortedByDescending { it.totalElapsedMillis }.forEach { job ->
            val label = labels[job.id] ?: job.id
            appendLine()
            appendLine("  ROM: $label")
            appendLine("    System: ${job.systemKey ?: "unknown"}")
            appendLine("    Result: ${outcomeLabel(job)}")
            appendLine("    Total: ${seconds(job.totalElapsedMillis)}")
            appendLine("    Attempts: ${job.attempts}")
            job.deferReason?.let { appendLine("    Reason: ${it.key}") }
            job.detail?.takeIf { it.isNotBlank() }?.let { appendLine("    Detail: ${it.trim()}") }

            // Whether a match was ever obtained is the single most misread thing in
            // this report: a slow lookup that succeeded and a lookup that timed out
            // used to be indistinguishable here, so a discarded result was reported
            // as a timeout. Stated outright, once per ROM.
            byRom[job.id]?.firstOrNull { it.type == DiagEventType.RomIdentified }
                ?.let { event ->
                    val provider = event.field(FIELD_PROVIDER) ?: "unknown"
                    appendLine(
                        "    Identified: yes \u2014 by $provider after " +
                            seconds(event.elapsedMillis ?: 0L),
                    )
                }
                ?: appendLine("    Identified: no")
            appendLine("    Artwork downloaded: ${yesNo(job.state == JobState.Completed)}")

            val providerLines = byRom[job.id]
                ?.filter { it.type == DiagEventType.ProviderResult }
                .orEmpty()
            if (providerLines.isEmpty()) {
                appendLine("    Providers attempted: none")
            } else {
                appendLine("    Providers attempted:")
                providerLines.forEach { event ->
                    val provider = event.field(FIELD_PROVIDER) ?: "unknown"
                    val stage = event.field(FIELD_STAGE)?.let { " ($it)" }.orEmpty()
                    val outcome = event.field(FIELD_OUTCOME) ?: "unknown"
                    appendLine(
                        "      $provider$stage: ${seconds(event.elapsedMillis ?: 0L)} / $outcome",
                    )
                }
            }

            appendRequests(byRom[job.id].orEmpty())
        }
        appendLine()
    }

    /**
     * Every HTTP request made for one ROM, in order.
     *
     * This is the part the previous report could not produce. A source's elapsed time
     * is the sum of up to twelve requests, and a sum cannot distinguish one slow
     * request from nine fast retries of a failing one — which are different problems
     * with different fixes. Each line carries what the network layer actually saw.
     */
    private fun StringBuilder.appendRequests(events: List<DiagEvent>) {
        val requests = events.filter { it.type == DiagEventType.ProviderRequest }
        val indexFetches = events.filter { it.type == DiagEventType.ProviderIndex }
        if (requests.isEmpty() && indexFetches.isEmpty()) return

        if (indexFetches.isEmpty()) {
            // Said explicitly. A missing line would be ambiguous between "the index
            // was already cached from an earlier ROM" and "indexes are not recorded".
            appendLine("    Index fetched: no (cached or not reached)")
        } else {
            indexFetches.forEach { event ->
                val folder = event.field(FIELD_FOLDER) ?: "unknown"
                val entries = event.field(FIELD_ENTRIES) ?: "0"
                val outcome = event.field(FIELD_OUTCOME) ?: "unknown"
                appendLine(
                    "    Index fetched: yes — $folder, $entries entries, " +
                        "${seconds(event.elapsedMillis ?: 0L)} / $outcome",
                )
                // The split is printed on its own line rather than folded into the
                // total: a total of four minutes against a one-second download is the
                // shape of the problem, and it is invisible until the halves are named.
                val fetch = event.field(FIELD_FETCH_MILLIS)?.toLongOrNull()
                val parse = event.field(FIELD_PARSE_MILLIS)?.toLongOrNull()
                if (fetch != null || parse != null) {
                    appendLine(
                        "        waiting + download ${seconds(fetch ?: 0L)}, " +
                            "parsing ${seconds(parse ?: 0L)}",
                    )
                }
            }
        }

        if (requests.isEmpty()) return
        appendLine("    Requests (${requests.size}):")
        requests.forEach { event ->
            val method = event.field(FIELD_METHOD) ?: "?"
            val stage = event.field(FIELD_STAGE) ?: "?"
            val target = event.field(FIELD_TARGET) ?: "?"
            val attempt = event.field(FIELD_ATTEMPT) ?: "1"
            val status = event.field(FIELD_STATUS)?.takeIf { it != "0" } ?: "—"
            val outcome = event.field(FIELD_OUTCOME) ?: "unknown"
            appendLine(
                "      $method $stage [$target] try $attempt: " +
                    "${seconds(event.elapsedMillis ?: 0L)} / $status / $outcome",
            )
            appendLine("        ${bytesLine(event)}")
        }
    }

    /**
     * The byte and compression detail for one request.
     *
     * Wire bytes are counted below transparent decompression, so printing them beside
     * the decoded size is what makes compression visible as a ratio rather than as a
     * claim: a 4 MB listing that crossed the wire as 270 KB says gzip is working, and
     * one that crossed as 4 MB says it is not.
     */
    private fun bytesLine(event: DiagEvent): String {
        val parts = buildList {
            event.field(FIELD_WIRE_BYTES)?.let { add("wire ${bytes(it)}") }
            event.field(FIELD_DECLARED_BYTES)?.let { add("declared ${bytes(it)}") }
            event.field(FIELD_BODY_BYTES)?.let { add("decoded ${bytes(it)}") }
            add("sent Accept-Encoding: ${event.field(FIELD_ACCEPT_ENCODING) ?: "unknown"}")
            add("got Content-Encoding: ${event.field(FIELD_CONTENT_ENCODING) ?: "unknown"}")
        }
        return parts.joinToString(", ")
    }

    private fun bytes(raw: String): String {
        val value = raw.toLongOrNull() ?: return raw
        return when {
            value < BYTES_PER_KB -> "$value B"
            value < BYTES_PER_KB * BYTES_PER_KB ->
                String.format(Locale.US, "%.1f KB", value / BYTES_PER_KB.toFloat())
            else -> String.format(
                Locale.US,
                "%.2f MB",
                value / (BYTES_PER_KB * BYTES_PER_KB).toFloat(),
            )
        }
    }

    /** A count per defer reason, so a pattern across many games is visible at a glance. */
    private fun StringBuilder.appendDeferredGroups(jobs: List<QueueJob>, labels: Map<String, String>) {
        val queued = jobs.filter { it.state == JobState.Deferred || it.state == JobState.Failed }
        if (queued.isEmpty()) return
        appendLine("RETRY QUEUE")
        DeferReason.entries.forEach { reason ->
            val matching = queued.filter { it.deferReason == reason }
            if (matching.isEmpty()) return@forEach
            appendLine("  ${reason.key} (${matching.size})")
            matching.forEach { job ->
                appendLine(
                    "    ${labels[job.id] ?: job.id} — ${job.attempts} attempt(s), " +
                        seconds(job.totalElapsedMillis),
                )
            }
        }
        appendLine()
    }

    /**
     * Stable per-ROM labels honouring the privacy setting.
     *
     * The numbering follows the original queue order rather than the sorted output,
     * so `Game 3` means the same row every time the report is regenerated.
     */
    private fun labelsFor(jobs: List<QueueJob>, includeFileNames: Boolean): Map<String, String> =
        jobs.mapIndexed { index, job ->
            job.id to if (includeFileNames) job.fileName else "Game ${index + 1}"
        }.toMap()

    private fun outcomeLabel(job: QueueJob): String = when (job.state) {
        JobState.Completed -> "Completed"
        JobState.Skipped -> "Skipped"
        JobState.Deferred -> "Deferred"
        JobState.Failed -> "Gave up"
        JobState.Ready -> "Not attempted"
        JobState.Processing -> "Interrupted while running"
    }

    /**
     * Last line of defence against a secret reaching a shared file.
     *
     * Nothing written by the app should ever match these, so a hit means a genuine
     * defect. Redacting rather than throwing keeps a useful report useful — losing
     * the whole report would be a worse outcome than losing one line of it.
     */
    private fun redact(text: String): String =
        SECRET_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc, "[redacted]") }

    /** True when the finished report is free of anything that looks like a secret. */
    fun assertNoSecrets(report: String): Boolean =
        SECRET_PATTERNS.none { it.containsMatchIn(report) }

    private val SECRET_PATTERNS: List<Regex> = listOf(
        // `devpassword=…`, `"apiKey": "…"`, `ssid = …` and similar, in any casing.
        Regex(
            """(?i)\b(dev[_-]?password|devpassword|password|passwd|api[_-]?key|apikey|""" +
                """access[_-]?token|token|secret|ssid|devid|dev[_-]?id)\b\s*[:=]\s*\S+""",
        ),
        // A SAF tree URI identifies the user's storage layout and is not needed here.
        Regex("""content://[^\s]+"""),
    )

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun seconds(millis: Long): String =
        String.format(Locale.US, "%.1f sec", millis / MILLIS_PER_SECOND.toFloat())

    private fun durationOf(start: Long?, end: Long?): String {
        if (start == null) return "—"
        val finish = end ?: System.currentTimeMillis()
        val elapsed = (finish - start).coerceAtLeast(0L)
        val totalSeconds = elapsed / MILLIS_PER_SECOND
        val minutes = totalSeconds / SECONDS_PER_MINUTE
        val remainder = totalSeconds % SECONDS_PER_MINUTE
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /**
     * Identifies the scheduling layer that produced the report.
     *
     * The app version alone was not enough to tell two device reports apart: the
     * background-scan queue shipped without a version bump, so its report looked
     * identical to the build before it. This moves whenever the queue's result
     * handling changes, independently of the marketing version.
     *
     *  - `queue-1` first queue build (deferral, snapshots, foreground service)
     *  - `queue-2` job settlement fixed: successes recorded as successes, defer
     *    reasons derived from evidence, cooperative budget checkpoints
     *  - `queue-3` requests bounded by a real call timeout, and the job budget stands
     *    down once a match has been earned
     *  - `queue-3m` identical scheduling and request behaviour to `queue-3`, with
     *    per-request measurement added. The suffix rather than a new number is
     *    deliberate: nothing about how the scan *behaves* changed, so a report from
     *    this build is directly comparable with a `queue-3` one, while still being
     *    distinguishable from it.
     *  - `queue-4` one scanning loop is guaranteed (the previous build could run two
     *    at once, which is what made a single ROM fetch the same index twice and
     *    report overlapping identify calls), each system's index is fetched once
     *    under a per-folder lock, and index parsing is off the IO thread, bounded,
     *    cancellable, and measured separately from the download it follows.
     *  - `queue-5` the index work itself. `queue-4`'s phase split proved the remaining
     *    delay was entirely local: 65.0 seconds parsing the PlayStation listing
     *    against a 1.1-second download. Reading a listing no longer runs a regular
     *    expression across several megabytes, a name is normalised once per entry
     *    rather than three times, matching rejects impossible candidates before
     *    measuring them, and the parse allowance scales with the size of the listing
     *    instead of failing a large system at a flat ceiling. A device that still
     *    cannot finish is reported as such and not retried, because retrying only
     *    re-proves it — which on the device cost 266 seconds for one ROM.
     */
    const val SCAN_ENGINE_REVISION = "queue-5"

    /** Field names used on [DiagEvent]s, shared with the writer so they cannot drift. */
    const val FIELD_PROVIDER = "provider"
    const val FIELD_STAGE = "stage"
    const val FIELD_OUTCOME = "outcome"
    const val FIELD_REASON = "reason"
    const val FIELD_SYSTEM = "system"
    const val FIELD_ATTEMPT = "attempt"

    /** Per-request fields. See [DiagEventType.ProviderRequest]. */
    const val FIELD_METHOD = "method"
    const val FIELD_TARGET = "target"
    const val FIELD_STATUS = "status"
    const val FIELD_WIRE_BYTES = "wireBytes"
    const val FIELD_DECLARED_BYTES = "declaredBytes"
    const val FIELD_BODY_BYTES = "bodyBytes"
    const val FIELD_ACCEPT_ENCODING = "acceptEncoding"
    const val FIELD_CONTENT_ENCODING = "contentEncoding"

    /** Per-index fields. See [DiagEventType.ProviderIndex]. */
    const val FIELD_FOLDER = "folder"
    const val FIELD_ENTRIES = "entries"

    /** The two halves of an index operation's elapsed time. */
    const val FIELD_FETCH_MILLIS = "fetchMillis"
    const val FIELD_PARSE_MILLIS = "parseMillis"

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val BYTES_PER_KB = 1_024L
}
