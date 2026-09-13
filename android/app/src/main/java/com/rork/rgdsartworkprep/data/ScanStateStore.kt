package com.rork.rgdsartworkprep.data

import android.content.Context
import android.util.Log
import com.rork.rgdsartworkprep.domain.queue.ScanSnapshot
import kotlinx.serialization.json.Json

/**
 * Writes the current scan down so it survives the process being stopped.
 *
 * Stored as one JSON document in its own SharedPreferences file rather than a
 * database: a snapshot is written a few times per ROM at most, is only ever read
 * whole, and adding a database would mean a schema, a migration story and a build
 * plugin for something that is two hundred lines of text.
 *
 * Nothing here may throw. A diagnostics or resume feature that can crash the app is
 * worse than one that quietly gives up, so every entry point swallows failure and
 * degrades to "no saved scan".
 */
class ScanStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persists the snapshot.
     *
     * @param immediate when true the write is flushed synchronously. Used at the
     *   moments where the process may not survive long enough for an async commit:
     *   cancellation, completion, and the service being torn down.
     */
    fun save(snapshot: ScanSnapshot, immediate: Boolean = false) {
        try {
            val editor = prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(snapshot))
            if (immediate) editor.commit() else editor.apply()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not save scan state: ${error.javaClass.simpleName}")
        }
    }

    /**
     * The saved scan, or null when there is none, it is unreadable, or it was written
     * by a version whose shape this build does not understand.
     */
    fun load(): ScanSnapshot? = try {
        prefs.getString(KEY_SNAPSHOT, null)
            ?.let { json.decodeFromString<ScanSnapshot>(it) }
            ?.takeIf { it.version == ScanSnapshot.CURRENT_VERSION }
    } catch (error: Throwable) {
        // A snapshot that cannot be parsed is discarded rather than repaired: resuming
        // from half-understood state risks redoing or skipping real work.
        Log.w(TAG, "Discarding unreadable scan state: ${error.javaClass.simpleName}")
        clear()
        null
    }

    /** The saved scan only if it is worth picking up again. */
    fun loadResumable(): ScanSnapshot? = load()?.takeIf { it.isResumable }

    fun clear() {
        try {
            prefs.edit().remove(KEY_SNAPSHOT).apply()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not clear scan state: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ScanStateStore"
        const val PREFS = "rgds_scan_state"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
