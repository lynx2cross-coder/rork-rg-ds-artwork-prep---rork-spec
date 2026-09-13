package com.rork.rgdsartworkprep.domain.diagnostics

import android.content.Context
import android.os.Build
import com.rork.rgdsartworkprep.BuildConfig
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.domain.ScrapeState
import com.rork.rgdsartworkprep.domain.queue.JobState

/**
 * Gathers everything a report needs from the live app and hands back plain text.
 *
 * Kept separate from [DiagnosticReport] so the formatting stays testable without a
 * device: this half knows about Android and settings, that half knows only about
 * data it is given.
 */
class DiagnosticExporter(
    context: Context,
    private val diagnostics: ScanDiagnostics,
) {

    private val appContext = context.applicationContext

    /**
     * Builds the report for the scan currently in [state].
     *
     * @param settings read for the privacy choice and to describe the configuration.
     *   Credential *values* are never read here — only whether each source is on.
     */
    fun export(state: ScrapeState, settings: AppSettings): String = DiagnosticReport.build(
        environment = environment(settings),
        summary = summarise(state),
        jobs = state.jobs,
        events = diagnostics.events(),
        includeFileNames = settings.includeFileNamesInReport,
        droppedEvents = diagnostics.droppedCount,
    )

    /**
     * Describes the build and device, plus which sources were switched on.
     *
     * Only the *names* of enabled sources are included. A key or password would say
     * nothing about why a scan was slow, so none is read.
     */
    private fun environment(settings: AppSettings): ReportEnvironment = ReportEnvironment(
        appName = appContext.getString(R.string.app_name),
        // Read from the constants compiled out of the build file rather than from the
        // packaged manifest values. A device report came back stamped
        // `build 1789174724` — a Unix timestamp — while the source declared 14,
        // because the packaging step rewrites the manifest's versionCode. That left
        // the one field whose entire job is identifying a build unable to do it.
        versionName = BuildConfig.SOURCE_VERSION_NAME,
        versionCode = BuildConfig.SOURCE_VERSION_CODE,
        // Kept alongside, so a build whose packaged code was rewritten still says so
        // instead of silently disagreeing with the source it was built from.
        packagedVersionCode = BuildConfig.VERSION_CODE,
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER ?: "unknown",
        model = Build.MODEL ?: "unknown",
        enabledProviders = buildList {
            if (settings.useHasheous) add("Hasheous")
            if (settings.hasTheGamesDbKey) add("TheGamesDB")
            if (settings.hasScreenScraperCredentials) add("ScreenScraper")
            if (settings.useLibretroThumbnails) add("Libretro thumbnails")
        },
        generatesGamelist = settings.generateGamelist,
        writesToLibrary = settings.libraryTreeUri != null && !settings.forceExportFallback,
    )

    private fun summarise(state: ScrapeState): ReportSummary {
        val jobs = state.jobs
        return ReportSummary(
            startedAtMillis = state.scanStartedAtWallMillis,
            endedAtMillis = state.scanEndedAtWallMillis,
            discovered = state.items.size,
            completed = jobs.count { it.state == JobState.Completed },
            skipped = jobs.count { it.state == JobState.Skipped },
            deferred = jobs.count { it.state == JobState.Deferred },
            failed = jobs.count { it.state == JobState.Failed },
            outcome = when {
                state.wasCancelled -> "Cancelled by the user"
                state.isRunning -> "Still running"
                else -> "Finished"
            },
        )
    }
}
