package com.rork.rgdsartworkprep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rork.rgdsartworkprep.MainActivity
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.domain.ScrapeState

/**
 * The notification that proves a scan is still running.
 *
 * This is the only thing the user can see once the screen is off, so it carries the
 * whole story: how far along the scan is, what it has achieved, and \u2014 crucially \u2014
 * that difficult games are queued rather than blocking. A bare "working\u2026" would not
 * answer the question the user actually has, which is whether anything is stuck.
 */
object ScanNotifications {

    const val CHANNEL_ID = "artwork_scan"
    const val NOTIFICATION_ID = 1001

    /**
     * Creates the channel. Safe to call repeatedly \u2014 Android treats a repeat create
     * of an existing channel as a no-op, so this can run on every service start.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.scan_channel_name),
            // Low: a scan is a background errand. It must be visible and dismissible
            // from the shade, but it has no business making a sound or pushing itself
            // in front of whatever the user is actually doing.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.scan_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: ScrapeState): Notification {
        val title = context.getString(R.string.app_name)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(headline(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail(state)))
            .setContentIntent(openAppIntent(context))
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state.isRunning) {
            builder.setProgress(state.total.coerceAtLeast(1), state.processed, state.total == 0)
            builder.addAction(
                0,
                context.getString(R.string.scan_notification_cancel),
                cancelIntent(context),
            )
        }
        return builder.build()
    }

    /** `Scanning 42 of 127 games`. */
    private fun headline(state: ScrapeState): String = when {
        !state.isRunning && state.wasCancelled -> "Scan cancelled"
        !state.isRunning -> "Scan complete"
        state.total == 0 -> "Preparing\u2026"
        else -> "Scanning ${(state.processed + 1).coerceAtMost(state.total)} of ${state.total} games"
    }

    /**
     * The counts, plus a line about the retry queue when there is one.
     *
     * Saying that difficult searches are *queued* is the point: it tells the user the
     * scan is still moving, which is exactly what the old behaviour could not promise.
     */
    private fun detail(state: ScrapeState): String = buildString {
        append("${state.savedCount} saved")
        append(" \u00b7 ${state.skippedCount} skipped")
        val queued = state.queuedForRetryCount
        if (queued > 0) append(" \u00b7 $queued queued")
        if (state.isRunning) {
            appendLine()
            append(
                if (queued > 0) {
                    val word = if (queued == 1) "search" else "searches"
                    "$queued difficult $word queued for retry \u2014 still working through the rest"
                } else {
                    "Working through the remaining games\u2026"
                },
            )
        } else if (queued > 0) {
            appendLine()
            append("$queued waiting in the retry queue")
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, immutableFlags())
    }

    private fun cancelIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScanService::class.java).apply {
            action = ScanService.ACTION_CANCEL
        }
        return PendingIntent.getService(context, 1, intent, immutableFlags())
    }

    /** Mutability must be declared from API 31; immutable is correct for both of these. */
    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
