package com.rork.rgdsartworkprep.data

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The smallest storage surface the ignore list needs.
 *
 * Exists so persistence can be exercised on the JVM: "restart" is a new repository
 * over the same store, and "upgrade" is a new repository over a store an older build
 * wrote. SharedPreferences itself cannot be constructed in a unit test.
 */
interface StringSetStore {
    fun read(key: String): Set<String>?
    fun write(key: String, value: Set<String>)
}

/** The on-device store. Lives in the app's private data, which survives updates. */
class SharedPreferencesStringSetStore(private val prefs: SharedPreferences) : StringSetStore {

    override fun read(key: String): Set<String>? = try {
        // Copied out: SharedPreferences hands back its own live instance and documents
        // it as unsafe to keep or mutate.
        prefs.getStringSet(key, null)?.toSet()
    } catch (error: ClassCastException) {
        Log.w(TAG, "Ignored-file list had an unexpected type; starting empty")
        null
    }

    override fun write(key: String, value: Set<String>) {
        // Committed synchronously: the user has just said "never show me this file
        // again", and a process killed a moment later must not forget that.
        if (!prefs.edit().putStringSet(key, HashSet(value)).commit()) {
            Log.w(TAG, "Could not save the ignored-file list")
        }
    }

    private companion object {
        const val TAG = "IgnoredFiles"
    }
}

/**
 * The user's persistent list of filenames to leave out of every scan.
 *
 * Kept in its own preferences file rather than inside [SettingsRepository]. Settings
 * rewrites every one of its keys on each change; keeping this list out of that write
 * means no settings change — present or future — can ever clobber it.
 */
class IgnoredFilesRepository(private val store: StringSetStore) {

    private val _ignored = MutableStateFlow(IgnoredFiles(store.read(KEY).orEmpty()))
    val ignored: StateFlow<IgnoredFiles> = _ignored.asStateFlow()

    val current: IgnoredFiles get() = _ignored.value

    /**
     * Adds [fileName] exactly as given.
     *
     * @return false when it was blank or already on the list under any casing — the
     *   existing entry is kept so its display form never changes behind the user's back.
     */
    @Synchronized
    fun add(fileName: String): Boolean {
        if (fileName.isBlank() || current.matches(fileName)) return false
        save(current.names + fileName)
        return true
    }

    /** Removes the entry matching [fileName] under any casing. */
    @Synchronized
    fun remove(fileName: String): Boolean {
        val key = IgnoredFiles.keyOf(fileName)
        val remaining = current.names.filterNot { IgnoredFiles.keyOf(it) == key }
        if (remaining.size == current.names.size) return false
        save(remaining)
        return true
    }

    private fun save(names: List<String>) {
        // Written before it is published, so nothing can act on an entry that a
        // restart would not remember.
        store.write(KEY, names.toSet())
        _ignored.value = IgnoredFiles(names)
    }

    companion object {
        /**
         * Both names are part of the upgrade contract: renaming either makes every
         * existing install silently forget its list. Pinned by a test.
         */
        const val PREFS = "rgds_ignored_files"
        const val KEY = "ignored_file_names"
    }
}
