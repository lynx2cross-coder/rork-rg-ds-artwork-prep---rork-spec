package com.rork.rgdsartworkprep.domain.diagnostics

import com.rork.rgdsartworkprep.domain.queue.DeferReason
import com.rork.rgdsartworkprep.domain.queue.JobState
import com.rork.rgdsartworkprep.domain.queue.QueueJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report has to answer one question without a computer attached: "why did this
 * particular ROM take eight minutes?" These assert it does, and that it never carries
 * anything it should not.
 */
class DiagnosticReportTest {

    private val environment = ReportEnvironment(
        appName = "ROM Art Prep",
        versionName = "1.5.0",
        versionCode = 20,
        androidRelease = "13",
        sdkInt = 33,
        manufacturer = "Anbernic",
        model = "RG DS",
        enabledProviders = listOf("Hasheous", "Libretro thumbnails"),
        generatesGamelist = true,
        writesToLibrary = true,
    )

    private val summary = ReportSummary(
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = 1_700_000_600_000L,
        discovered = 2,
        completed = 1,
        skipped = 0,
        deferred = 1,
        failed = 0,
        outcome = "Finished",
    )

    private val fastJob = QueueJob(
        id = "fast",
        fileName = "Super Mario Bros.nes",
        systemKey = "nes",
        state = JobState.Completed,
        attempts = 1,
        totalElapsedMillis = 12_400L,
    )

    private val slowJob = QueueJob(
        id = "slow",
        fileName = "Final Fantasy VII.pbp",
        systemKey = "psx",
        state = JobState.Deferred,
        attempts = 2,
        deferReason = DeferReason.ProviderTimeout,
        detail = "Timed out",
        totalElapsedMillis = 93_100L,
    )

    private fun providerEvent(
        romId: String,
        provider: String,
        elapsed: Long,
        outcome: String,
    ): DiagEvent = DiagEvent(
        type = DiagEventType.ProviderResult,
        atMillis = 1_700_000_000_000L,
        elapsedMillis = elapsed,
        romId = romId,
        fields = listOf(
            DiagnosticReport.FIELD_PROVIDER to provider,
            DiagnosticReport.FIELD_STAGE to "identify",
            DiagnosticReport.FIELD_OUTCOME to outcome,
        ),
    )

    private val events = listOf(
        providerEvent("fast", "hasheous", 1_100L, "NotFound"),
        providerEvent("fast", "libretrothumbnails", 9_800L, "Success"),
        providerEvent("slow", "hasheous", 12_700L, "NotFound"),
        providerEvent("slow", "screenscraper", 40_200L, "Timeout"),
        providerEvent("slow", "libretrothumbnails", 40_000L, "Timeout"),
    )

    private fun requestEvent(
        romId: String,
        stage: String,
        attempt: Int,
        method: String,
        target: String,
        status: Int,
        elapsed: Long,
        outcome: String,
        wireBytes: Long? = null,
        bodyBytes: Long? = null,
        acceptEncoding: String = "gzip",
        contentEncoding: String = "gzip",
    ): DiagEvent = DiagEvent(
        type = DiagEventType.ProviderRequest,
        atMillis = 1_700_000_000_000L,
        elapsedMillis = elapsed,
        romId = romId,
        fields = buildList {
            add(DiagnosticReport.FIELD_PROVIDER to "libretrothumbnails")
            add(DiagnosticReport.FIELD_STAGE to stage)
            add(DiagnosticReport.FIELD_ATTEMPT to attempt.toString())
            add(DiagnosticReport.FIELD_METHOD to method)
            add(DiagnosticReport.FIELD_TARGET to target)
            add(DiagnosticReport.FIELD_STATUS to status.toString())
            add(DiagnosticReport.FIELD_OUTCOME to outcome)
            wireBytes?.let { add(DiagnosticReport.FIELD_WIRE_BYTES to it.toString()) }
            bodyBytes?.let { add(DiagnosticReport.FIELD_BODY_BYTES to it.toString()) }
            add(DiagnosticReport.FIELD_ACCEPT_ENCODING to acceptEncoding)
            add(DiagnosticReport.FIELD_CONTENT_ENCODING to contentEncoding)
        },
    )

    private fun indexEvent(
        romId: String,
        folder: String,
        entries: Int,
        elapsed: Long,
        outcome: String,
        fetchMillis: Long? = null,
        parseMillis: Long? = null,
    ): DiagEvent = DiagEvent(
        type = DiagEventType.ProviderIndex,
        atMillis = 1_700_000_000_000L,
        elapsedMillis = elapsed,
        romId = romId,
        fields = buildList {
            add(DiagnosticReport.FIELD_PROVIDER to "libretrothumbnails")
            add(DiagnosticReport.FIELD_FOLDER to folder)
            add(DiagnosticReport.FIELD_ENTRIES to entries.toString())
            fetchMillis?.let { add(DiagnosticReport.FIELD_FETCH_MILLIS to it.toString()) }
            parseMillis?.let { add(DiagnosticReport.FIELD_PARSE_MILLIS to it.toString()) }
            add(DiagnosticReport.FIELD_OUTCOME to outcome)
        },
    )

    private fun build(includeFileNames: Boolean = true): String = DiagnosticReport.build(
        environment = environment,
        summary = summary,
        jobs = listOf(fastJob, slowJob),
        events = events,
        includeFileNames = includeFileNames,
    )

    @Test
    fun `records provider timing per rom`() {
        val report = build()
        assertTrue(report.contains("hasheous (identify): 12.7 sec / NotFound"))
        assertTrue(report.contains("screenscraper (identify): 40.2 sec / Timeout"))
        assertTrue(report.contains("libretrothumbnails (identify): 40.0 sec / Timeout"))
    }

    @Test
    fun `records rom timing`() {
        val report = build()
        assertTrue(report.contains("Total: 93.1 sec"))
        assertTrue(report.contains("Total: 12.4 sec"))
    }

    @Test
    fun `records the defer reason`() {
        val report = build()
        assertTrue(report.contains("Reason: provider_timeout"))
        assertTrue(report.contains("Result: Deferred"))
    }

    @Test
    fun `records the retry count`() {
        val report = build()
        assertTrue(report.contains("Attempts: 2"))
        assertTrue(report.contains("2 attempt(s)"))
    }

    @Test
    fun `report can be generated with app scan and per rom sections`() {
        val report = build()
        assertTrue(report.contains("APP INFORMATION"))
        assertTrue(report.contains("Version: 1.5.0 (build 20)"))
        // The queue build shipped without a version bump, so its report could not be
        // told apart from the previous one. This line makes the engine explicit.
        assertTrue(report.contains("Scan engine: queue-5"))
        assertTrue(report.contains("Device: Anbernic RG DS"))
        assertTrue(report.contains("Android: 13 (API 33)"))
        assertTrue(report.contains("SCAN INFORMATION"))
        assertTrue(report.contains("Discovered: 2"))
        assertTrue(report.contains("Queued for retry: 1"))
        assertTrue(report.contains("PER-ROM INFORMATION"))
        assertTrue(report.contains("RETRY QUEUE"))
    }

    /**
     * A build whose packaged version was rewritten must say so. The 1.2.2 device
     * report arrived stamped `build 1789174724` while the source declared 14, and a
     * silent disagreement in the one field that identifies a build is what made two
     * reports impossible to tell apart.
     */
    @Test
    fun `a rewritten packaged version is reported alongside the source one`() {
        val report = DiagnosticReport.build(
            environment = environment.copy(packagedVersionCode = 1_789_174_724),
            summary = summary,
            jobs = listOf(fastJob),
            events = emptyList(),
            includeFileNames = true,
        )

        assertTrue(report.contains("Version: 1.5.0 (build 20)"))
        assertTrue(report.contains("Packaged build: 1789174724"))
    }

    /** An ordinary build must not carry the extra line at all. */
    @Test
    fun `a build whose versions agree reports no packaging note`() {
        assertFalse(build().contains("Packaged build:"))
    }

    /** The slowest ROM is what the reader is looking for, so it leads. */
    @Test
    fun `slowest rom is listed first`() {
        val report = build()
        assertTrue(report.indexOf("Final Fantasy VII") < report.indexOf("Super Mario Bros"))
    }

    @Test
    fun `credentials and api keys are never written to the report`() {
        val leaky = listOf(
            DiagEvent(
                type = DiagEventType.ProviderResult,
                atMillis = 0L,
                elapsedMillis = 10L,
                romId = "fast",
                fields = listOf(
                    DiagnosticReport.FIELD_PROVIDER to "screenscraper",
                    // A defect elsewhere putting a secret on an event must still not
                    // reach a shared file.
                    "devpassword" to "hunter2",
                    "apikey" to "abcdef123456",
                    "tree" to "content://com.android.externalstorage/tree/primary%3ARoms",
                ),
            ),
        )

        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(fastJob),
            events = leaky,
            includeFileNames = true,
        )

        assertFalse(report.contains("hunter2"))
        assertFalse(report.contains("abcdef123456"))
        assertFalse(report.contains("content://"))
        assertTrue(DiagnosticReport.assertNoSecrets(report))
    }

    @Test
    fun `a clean report passes the secret check`() {
        assertTrue(DiagnosticReport.assertNoSecrets(build()))
    }

    @Test
    fun `filenames are withheld by default but timings survive`() {
        val report = build(includeFileNames = false)

        assertFalse(report.contains("Final Fantasy VII.pbp"))
        assertFalse(report.contains("Super Mario Bros.nes"))
        assertTrue(report.contains("Game 1"))
        assertTrue(report.contains("Game 2"))
        // The question the report exists to answer is still answerable.
        assertTrue(report.contains("Total: 93.1 sec"))
        assertTrue(report.contains("Reason: provider_timeout"))
        assertTrue(report.contains("Include ROM filenames"))
    }

    @Test
    fun `a truncated log says so rather than quietly lying`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(fastJob),
            events = events,
            includeFileNames = true,
            droppedEvents = 120,
        )
        assertTrue(report.contains("120 older log entries were dropped"))
    }

    @Test
    fun `excluded material is declared to the user`() {
        val report = build()
        assertTrue(
            report.contains(
                "No ROM contents, artwork, credentials or system logs are included",
            ),
        )
    }

    @Test
    fun `a rom with no provider calls still reports`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary.copy(discovered = 1, completed = 0, skipped = 1, deferred = 0),
            jobs = listOf(
                QueueJob("x", "unknown.bin", null, JobState.Skipped, attempts = 1, detail = "Unknown system"),
            ),
            events = emptyList(),
            includeFileNames = true,
        )
        assertTrue(report.contains("Providers attempted: none"))
        assertTrue(report.contains("System: unknown"))
    }

    @Test
    fun `duration is rendered in minutes and seconds`() {
        val report = build()
        assertEquals(true, report.contains("Duration: 10m 0s"))
    }

    /**
     * The whole point of the measurement build: a source's elapsed time is the sum of
     * several requests, and the report has to be able to say which one was slow.
     */
    @Test
    fun `each request is listed separately with its own timing`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                requestEvent(
                    "slow", "name guess", 1, "GET", "guess 1 of 3", 404, 500L, "Absent",
                    wireBytes = 315L,
                ),
                requestEvent(
                    "slow", "name guess", 2, "GET", "guess 2 of 3", 0, 60_000L, "Timeout",
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("Requests (2):"))
        assertTrue(
            report.contains("GET name guess [guess 1 of 3] try 1: 0.5 sec / 404 / Absent"),
        )
        // A retry must be visibly a retry: nine fast retries and one slow request sum
        // to the same number and need different fixes.
        assertTrue(
            report.contains("GET name guess [guess 2 of 3] try 2: 60.0 sec / — / Timeout"),
        )
    }

    /**
     * Compression is the measurement most likely to change the reading of a slow
     * index fetch, so wire and decoded sizes are printed side by side.
     */
    @Test
    fun `request bytes and encodings are reported`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                requestEvent(
                    "slow", "index", 1, "GET", "system index", 200, 3_000L, "Read",
                    wireBytes = 269_594L,
                    bodyBytes = 4_054_023L,
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("wire 263.3 KB"))
        assertTrue(report.contains("decoded 3.87 MB"))
        assertTrue(report.contains("sent Accept-Encoding: gzip"))
        assertTrue(report.contains("got Content-Encoding: gzip"))
    }

    /** "The device never asked for gzip" is a finding, and must read as one. */
    @Test
    fun `an uncompressed response is visible as such`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                requestEvent(
                    "slow", "index", 1, "GET", "system index", 200, 240_000L, "Read",
                    wireBytes = 4_054_023L,
                    bodyBytes = 4_054_023L,
                    acceptEncoding = "none",
                    contentEncoding = "none",
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("sent Accept-Encoding: none"))
        assertTrue(report.contains("got Content-Encoding: none"))
        assertTrue(report.contains("wire 3.87 MB"))
    }

    @Test
    fun `an index fetch is reported with its entry count and timing`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                indexEvent("slow", "Nintendo - Nintendo Entertainment System", 13_418, 2_970L, "Success"),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("Index fetched: yes"))
        assertTrue(report.contains("13418 entries"))
        // 2,970ms renders as 3.0: the formatter rounds rather than truncates.
        assertTrue(report.contains("3.0 sec / Success"))
    }

    /**
     * A ROM that made requests but fetched no index must say so: silence there is
     * ambiguous between "already cached" and "indexes are not recorded at all".
     */
    @Test
    fun `a rom that did not fetch the index says so explicitly`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(fastJob),
            events = listOf(
                requestEvent(
                    "fast", "name guess", 1, "GET", "guess 1 of 3", 200, 1_400L, "Present",
                    wireBytes = 444_272L,
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("Index fetched: no (cached or not reached)"))
    }

    /**
     * The finding the previous report could not express: an index whose total is
     * minutes while its own download was a second. A single figure left that
     * difference unattributed between waiting for the request slot and parsing on the
     * device's CPU — two problems with opposite fixes.
     */
    @Test
    fun `an index fetch separates waiting and download from parsing`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                indexEvent(
                    "slow", "Sony - PlayStation", 9_339, 334_700L, "Success",
                    fetchMillis = 1_100L,
                    parseMillis = 333_600L,
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("waiting + download 1.1 sec"))
        assertTrue(report.contains("parsing 333.6 sec"))
    }

    /**
     * A parse that outran its ceiling is not a missing index: the archive answered
     * correctly and this device was the bottleneck, so it must not be reported as a
     * system whose covers do not exist.
     */
    @Test
    fun `an index whose parse exceeded its ceiling is named as such`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                indexEvent(
                    "slow", "Sony - PlayStation", 0, 61_100L, "ParseTimeout",
                    fetchMillis = 1_100L,
                    parseMillis = 60_000L,
                ),
            ),
            includeFileNames = true,
        )

        assertTrue(report.contains("ParseTimeout"))
        assertTrue(report.contains("parsing 60.0 sec"))
    }

    /** An index event carrying no phase fields must still render its total. */
    @Test
    fun `an index event without phase detail still reports its total`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(indexEvent("slow", "Sony - PlayStation", 9_339, 12_000L, "Success")),
            includeFileNames = true,
        )

        assertTrue(report.contains("12.0 sec / Success"))
        assertFalse(report.contains("waiting + download"))
    }

    /** An index that downloads fine and parses to nothing must not read as a success. */
    @Test
    fun `an empty index is distinguishable from a successful one`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(indexEvent("slow", "Sony - PlayStation", 0, 5_000L, "Empty")),
            includeFileNames = true,
        )

        assertTrue(report.contains("0 entries"))
        assertTrue(report.contains("/ Empty"))
    }

    /**
     * Request traces carry a request *shape*, never a filename, so they must survive
     * the privacy setting intact — otherwise the measurement build would be useless
     * to the users most likely to withhold names.
     */
    @Test
    fun `request detail survives withheld filenames`() {
        val report = DiagnosticReport.build(
            environment = environment,
            summary = summary,
            jobs = listOf(slowJob),
            events = listOf(
                requestEvent(
                    "slow", "index", 1, "GET", "system index", 200, 3_000L, "Read",
                    wireBytes = 269_594L,
                ),
                indexEvent("slow", "Sony - PlayStation", 9_339, 3_000L, "Success"),
            ),
            includeFileNames = false,
        )

        assertFalse(report.contains("Final Fantasy VII.pbp"))
        assertTrue(report.contains("GET index [system index] try 1"))
        assertTrue(report.contains("9339 entries"))
    }
}
