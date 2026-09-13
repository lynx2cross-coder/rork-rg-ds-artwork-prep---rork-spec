package com.rork.rgdsartworkprep.data

import android.content.Context
import android.util.Log
import com.rork.rgdsartworkprep.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last fatal error so it survives a restart and can be read back in Settings.
 *
 * Nothing in here may throw: diagnostics must never become the thing that breaks the
 * app. Writes are synchronous because a crash usually kills the process immediately
 * afterwards, and an async write would be lost.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val PREFS = "rgds_diagnostics"
    private const val KEY_LAST = "last_error"
    private const val MAX_FRAMES = 12

    /** @param stage where it happened, e.g. `scrape-run`, so the report is actionable. */
    fun record(context: Context, stage: String, error: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val frames = error.stackTrace.take(MAX_FRAMES).joinToString("\n") { "  at $it" }
            val cause = error.cause
                ?.let { "\nCaused by: ${it.javaClass.name}: ${it.message}" }
                .orEmpty()
            val report = buildString {
                // The build stamp comes first: a report that does not say which build it
                // came from cannot be acted on, since the answer to "is this already
                // fixed?" depends entirely on the version the user is running.
                appendLine("$timestamp · $stage · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("${error.javaClass.name}: ${error.message}")
                if (cause.isNotEmpty()) appendLine(cause.trim())
                append(frames)
            }
            prefs(context).edit().putString(KEY_LAST, report).commit()
            Log.e(TAG, "Recorded failure during $stage", error)
        } catch (ignored: Throwable) {
            // Recording a problem must never create a second one.
        }
    }

    fun last(context: Context): String? = try {
        prefs(context).getString(KEY_LAST, null)
    } catch (error: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_LAST).apply()
        } catch (ignored: Throwable) {
            // Nothing to do — the report is only a convenience.
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
