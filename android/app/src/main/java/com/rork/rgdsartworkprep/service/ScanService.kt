package com.rork.rgdsartworkprep.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.rork.rgdsartworkprep.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a scan alive independently of any Activity.
 *
 * ## Why a foreground service
 *
 * The scan is work the user explicitly asked for, expects to finish, and wants to
 * watch — which is exactly what a foreground service is for. The device bug report
 * from the RG DS showed the app sitting at `curProcState=16` / `oom adj 900`, i.e.
 * cached, with Android free to stop it; anything running only inside the Activity
 * gets exactly that treatment once the screen goes off.
 *
 * `dataSync` is the matching foreground service type for user-initiated transfer
 * work on API 34+, and is declared in the manifest. On older releases the type is
 * simply not passed, which is the required behaviour there.
 *
 * ### Why not the alternatives
 *
 * - **A wake lock** keeps the *display* or CPU awake and was explicitly ruled out.
 *   It also does not stop Android reclaiming a cached process.
 * - **WorkManager** is designed to run work at a time of the *system's* choosing and
 *   may defer it for minutes. A scan the user is watching must start now, and the
 *   user must be able to see and cancel it — which is the foreground-service
 *   contract, not the deferred-work one.
 * - **`setForeground` from a Worker** would end up creating this same notification
 *   and this same foreground type, with an extra scheduler in between.
 *
 * The service owns nothing: the scan lives in [com.rork.rgdsartworkprep.domain.ScrapeCoordinator],
 * which is a process singleton. The service exists only to tell Android the process
 * is busy and to show the notification, so the Activity is free to come and go.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScanNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancelling is the user's decision, not a provider failure: the
                // coordinator records it as such and leaves saved artwork untouched.
                AppGraph.scraper.cancel()
                stopScan()
                return START_NOT_STICKY
            }
            else -> startWatching()
        }
        // Not sticky: a scan is resumed deliberately from its saved snapshot, with the
        // attempt counts intact, rather than by Android restarting a bare service with
        // a null intent and no idea what it was doing.
        return START_NOT_STICKY
    }

    /**
     * Promotes the process and mirrors scan state into the notification.
     *
     * The first notification is posted synchronously because Android requires one
     * within a few seconds of the service starting.
     */
    private fun startWatching() {
        if (watcher != null) return
        promote(AppGraph.scraper.state.value)

        watcher = scope.launch {
            launch {
                AppGraph.scraper.state.collectLatest { state ->
                    if (AppGraph.scraper.isScanning.value) {
                        notifyProgress(state)
                    }
                }
            }
            // The coordinator is the authority on whether work remains. When it says
            // the scan is over the service has nothing left to keep the process up for.
            AppGraph.scraper.isScanning.collectLatest { running ->
                if (!running) {
                    finishNotification()
                    stopScan()
                }
            }
        }
    }

    private fun promote(state: com.rork.rgdsartworkprep.domain.ScrapeState) {
        val notification = ScanNotifications.build(this, state)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    ScanNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ServiceCompat.startForeground(this, ScanNotifications.NOTIFICATION_ID, notification, 0)
            }
        } catch (error: Throwable) {
            // Android can refuse a foreground start if the app was already in the
            // background when the request arrived. The scan itself is unaffected —
            // it runs in a process-wide scope — so this degrades to "no notification"
            // rather than taking the run down with it.
            Log.w(TAG, "Could not start in the foreground: ${error.javaClass.simpleName}")
        }
    }

    private fun notifyProgress(state: com.rork.rgdsartworkprep.domain.ScrapeState) {
        if (!hasNotificationPermission()) return
        try {
            NotificationManagerCompat.from(this)
                .notify(ScanNotifications.NOTIFICATION_ID, ScanNotifications.build(this, state))
        } catch (error: SecurityException) {
            // The permission can be revoked between the check above and this call.
            // Losing the notification must never take the scan down with it.
            Log.w(TAG, "Not allowed to post the notification: ${error.javaClass.simpleName}")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not update the notification: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Leaves a final, dismissible summary behind.
     *
     * The ongoing flag is dropped by [ScanNotifications] once the scan is not running,
     * so what remains can be swiped away — and says whether anything is queued.
     */
    private fun finishNotification() {
        val state = AppGraph.scraper.state.value
        if (state.items.isEmpty() || !hasNotificationPermission()) {
            NotificationManagerCompat.from(this).cancel(ScanNotifications.NOTIFICATION_ID)
            return
        }
        try {
            NotificationManagerCompat.from(this)
                .notify(ScanNotifications.NOTIFICATION_ID, ScanNotifications.build(this, state))
        } catch (error: SecurityException) {
            Log.w(TAG, "Not allowed to post the final notification: ${error.javaClass.simpleName}")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not post the final notification: ${error.javaClass.simpleName}")
        }
    }

    /**
     * From API 33 the user can refuse notifications outright. The scan still runs —
     * the foreground type is what keeps the process alive, not the user seeing it.
     */
    private fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun stopScan() {
        watcher?.cancel()
        watcher = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScanService"
        const val ACTION_CANCEL = "com.rork.rgdsartworkprep.action.CANCEL_SCAN"

        /**
         * Starts the service for a scan that has just been started.
         *
         * Failure here is deliberately survivable: if Android refuses the start the
         * scan still runs in the process-wide scope, it simply loses the protection
         * and the notification.
         */
        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ScanService::class.java)
            try {
                // startForegroundService only exists from API 26. Below that a plain
                // start already gives the process the same protection, because the
                // background execution limits it exists to satisfy were introduced in
                // that release. minSdk here is 24, so both paths are real.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not start the scan service: ${error.javaClass.simpleName}")
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context.applicationContext, ScanService::class.java).apply {
                action = ACTION_CANCEL
            }
            try {
                context.applicationContext.startService(intent)
            } catch (error: Throwable) {
                Log.w(TAG, "Could not deliver cancel: ${error.javaClass.simpleName}")
            }
        }
    }
}
