package com.rork.rgdsartworkprep.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Remembers successful identifications so a ROM is never re-searched.
 *
 * Keyed by [RomIdentityKey], which names one library entry — system, filename, size
 * and then its checksum. Earlier builds keyed on the checksum alone, which let two
 * files with identical bytes share an entry and therefore share artwork.
 */
class MatchCacheRepository(context: Context) {

    private val prefs = context.getSharedPreferences("rgds_match_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    @Volatile
    private var cache: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> = try {
        val raw = prefs.getString(KEY_MAP, null)
        if (raw.isNullOrBlank()) {
            mutableMapOf()
        } else {
            json.decodeFromString(serializer, raw).let(::withoutSupersededKeys)
        }
    } catch (error: Exception) {
        Log.w(TAG, "Match cache unreadable, starting fresh: ${error.javaClass.simpleName}")
        mutableMapOf()
    }

    /**
     * Drops entries written under the old checksum-only key.
     *
     * Those entries cannot be migrated: a `crc:<crc>` key does not say which file it
     * was recorded for, and on a device where two ROMs shared bytes it may have been
     * recorded for the wrong one. Re-identifying a ROM costs one search; keeping a
     * match that might belong to another game costs the user wrong artwork they have
     * already reported once.
     */
    private fun withoutSupersededKeys(stored: Map<String, String>): MutableMap<String, String> {
        val current = stored.filterKeys { it.startsWith("${RomIdentityKey.PREFIX}:") }
        val dropped = stored.size - current.size
        if (dropped > 0) {
            Log.i(TAG, "Discarded $dropped remembered match(es) keyed by content alone")
        }
        return current.toMutableMap()
    }

    private fun persist() {
        try {
            prefs.edit().putString(KEY_MAP, json.encodeToString(serializer, cache)).apply()
        } catch (error: Exception) {
            Log.w(TAG, "Could not persist match cache: ${error.javaClass.simpleName}")
        }
    }

    fun identityKey(crc32: String?, fileName: String, size: Long, systemKey: String?): String =
        RomIdentityKey.of(crc32, fileName, size, systemKey)

    fun lookup(key: String): String? = cache[key]

    fun remember(key: String, gameId: String) {
        cache[key] = gameId
        persist()
    }

    val size: Int get() = cache.size

    fun clear() {
        cache = mutableMapOf()
        prefs.edit().remove(KEY_MAP).apply()
    }

    private companion object {
        const val TAG = "MatchCache"
        const val KEY_MAP = "identity_to_game_id"
    }
}
