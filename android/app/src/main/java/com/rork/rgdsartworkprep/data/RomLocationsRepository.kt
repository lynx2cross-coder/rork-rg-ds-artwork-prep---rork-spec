package com.rork.rgdsartworkprep.data

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The smallest storage surface the location list needs, so persistence, restarts and
 * the upgrade migration can all be exercised on the JVM.
 */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** The on-device store, in the app's private data, which survives updates. */
class SharedPreferencesTextStore(private val prefs: SharedPreferences) : TextStore {

    override fun read(key: String): String? = try {
        prefs.getString(key, null)
    } catch (error: ClassCastException) {
        Log.w(TAG, "ROM location list had an unexpected type; starting empty")
        null
    }

    override fun write(key: String, value: String) {
        // Committed synchronously: a folder the user just added must still be there if
        // the process is killed a moment later.
        if (!prefs.edit().putString(key, value).commit()) {
            Log.w(TAG, "Could not save the ROM location list")
        }
    }

    private companion object {
        const val TAG = "RomLocations"
    }
}

/** What happened when the user picked a folder to add. */
sealed interface AddLocationResult {
    data class Added(val location: RomLocation) : AddLocationResult

    /** The folder is already saved; nothing was added or replaced. */
    data class AlreadySaved(val existing: RomLocation) : AddLocationResult

    /** The picker returned something that is not a folder tree. */
    data object Invalid : AddLocationResult
}

/**
 * The user's saved ROM root folders.
 *
 * Kept in its own preferences file, like the ignore list: settings rewrites every key
 * on each change, and no settings change may ever drop a saved folder.
 *
 * @param legacyTreeUri the single library folder an earlier build stored, read only
 *   the first time this list is created. Once the list has been written \u2014 even
 *   empty \u2014 the old setting is never consulted again, so removing the migrated folder
 *   does not bring it back on the next launch.
 * @param clock injectable so tests control the order locations were added in
 */
class RomLocationsRepository(
    private val store: TextStore,
    legacyTreeUri: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _locations = MutableStateFlow(load(legacyTreeUri))
    val locations: StateFlow<List<RomLocation>> = _locations.asStateFlow()

    val current: List<RomLocation> get() = _locations.value

    private fun load(legacyTreeUri: String?): List<RomLocation> {
        val stored = store.read(KEY)
        if (stored != null) return decode(stored)
        val migrated = legacyTreeUri
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { TreeRef.parse(it) != null }
            ?.let { listOf(RomLocation(treeUri = it, addedAtMillis = 0L, legacyIdentity = true)) }
            .orEmpty()
        // Written straight away, so the migration happens exactly once.
        store.write(KEY, encode(migrated))
        return migrated
    }

    /**
     * Adds a folder alongside the ones already saved. Never replaces one.
     *
     * The same folder is refused however its URI is spelled, so the list can never
     * scan one folder twice.
     */
    @Synchronized
    fun add(treeUri: String): AddLocationResult {
        if (TreeRef.parse(treeUri) == null) return AddLocationResult.Invalid
        val key = RomLocations.keyOf(treeUri)
        current.firstOrNull { it.key == key }?.let { return AddLocationResult.AlreadySaved(it) }
        // The first folder of an empty list behaves exactly like the single library
        // folder always did; later ones are scoped so they can never collide with it.
        val location = RomLocation(
            treeUri = treeUri,
            addedAtMillis = clock(),
            legacyIdentity = current.isEmpty(),
        )
        save(current + location)
        return AddLocationResult.Added(location)
    }

    /**
     * Forgets one saved folder. Nothing on disk is touched \u2014 no ROM, cover or
     * gamelist.xml \u2014 and every other saved folder is left exactly as it was.
     */
    @Synchronized
    fun remove(treeUri: String): Boolean {
        val key = RomLocations.keyOf(treeUri)
        val remaining = current.filterNot { it.key == key }
        if (remaining.size == current.size) return false
        save(remaining)
        return true
    }

    private fun save(locations: List<RomLocation>) {
        // Written before it is published, so nothing acts on a folder a restart would
        // not remember.
        store.write(KEY, encode(locations))
        _locations.value = locations
    }

    @Serializable
    private data class Stored(val version: Int = 1, val locations: List<RomLocation> = emptyList())

    private fun encode(locations: List<RomLocation>): String =
        json.encodeToString(Stored.serializer(), Stored(locations = locations))

    private fun decode(raw: String): List<RomLocation> = try {
        json.decodeFromString(Stored.serializer(), raw).locations
            // A hand-edited or corrupted entry is dropped rather than scanned.
            .filter { TreeRef.parse(it.treeUri) != null }
            .distinctBy { it.key }
    } catch (error: Exception) {
        // Logging must never be the thing that fails here: the fallback below is what
        // keeps a damaged list from crashing the app on launch.
        runCatching { Log.w(TAG, "ROM location list could not be read: ${error.javaClass.simpleName}") }
        // Fall back to the bare-list shape, then to nothing; never crash on launch.
        runCatching { json.decodeFromString(ListSerializer(RomLocation.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        /**
         * Both names are part of the upgrade contract: renaming either makes every
         * existing install forget its folders. Pinned by a test.
         */
        const val PREFS = "rgds_rom_locations"
        const val KEY = "rom_locations"

        private const val TAG = "RomLocations"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
