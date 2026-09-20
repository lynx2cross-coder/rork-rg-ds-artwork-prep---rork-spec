package com.rork.rgdsartworkprep.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the user configures once and the app then remembers. */
data class AppSettings(
    val libraryTreeUri: String? = null,
    val libraryLabel: String? = null,
    val devId: String = "",
    val devPassword: String = "",
    val userId: String = "",
    val userPassword: String = "",
    val preferredRegion: String = "wor",
    val forceExportFallback: Boolean = false,
    val generateGamelist: Boolean = false,
    /** The credential-free source, on by default so the app works out of the box. */
    val useHasheous: Boolean = true,
    /** Optional: a free key the user requests from thegamesdb.net themselves. */
    val theGamesDbApiKey: String = "",
    /**
     * The credential-free box-art archive, on by default.
     *
     * It needs no account and is artwork-only, so it costs nothing to leave on and is
     * what lets a coverless match still end up with a picture.
     */
    val useLibretroThumbnails: Boolean = true,
    /**
     * Records per-provider timings inside the app so a slow scan can be explained
     * without a computer. Off by default: an ordinary scan should not pay for it.
     */
    val detailedDiagnostics: Boolean = false,
    /**
     * Whether an exported report names the games.
     *
     * Off by default. A filename is the one genuinely personal thing in a report, and
     * "why was this slow" can be answered without it.
     */
    val includeFileNamesInReport: Boolean = false,
    /**
     * Systems the user has switched off for artwork lookup.
     *
     * Opt-outs are stored, never opt-ins, and the distinction is the whole design.
     * Recording the enabled systems would freeze the catalog at the moment the user
     * last touched this screen: every system added in a later release would arrive
     * silently switched off, and an app that quietly stops looking up a console after
     * an update is indistinguishable from a broken one. Storing the exceptions means
     * the default is always "everything this release can scrape", no preference at all
     * reads as today's behaviour, and a new catalog entry is on the moment it ships.
     *
     * Detection is untouched by this. A ROM for a disabled system is still recognised
     * and still listed — only the artwork query is skipped.
     */
    val disabledSystemKeys: Set<String> = emptySet(),
) {
    /** ScreenScraper requires developer credentials; user credentials raise the quota. */
    val hasScreenScraperCredentials: Boolean
        get() = devId.isNotBlank() && devPassword.isNotBlank()

    val hasTheGamesDbKey: Boolean get() = theGamesDbApiKey.isNotBlank()

    /**
     * True when at least one artwork source is usable. No credentials are required for
     * this: the two free sources cover it on their own.
     */
    val hasAnyProvider: Boolean
        get() = useHasheous || useLibretroThumbnails || hasTheGamesDbKey ||
            hasScreenScraperCredentials

    /** True when a source that can supply a cover for an identified game is available. */
    val hasArtworkFallback: Boolean
        get() = useLibretroThumbnails || hasTheGamesDbKey || hasScreenScraperCredentials

    /**
     * Whether artwork may be looked up for this system.
     *
     * The catalog's own [com.rork.rgdsartworkprep.model.GameSystem.scrapingEnabled] is a
     * property of the release — whether the app knows how to address the system at all.
     * This is a property of the user's choice. Both must agree before a query is made,
     * and they are kept apart because only one of them can be turned back on from the
     * Settings screen.
     */
    fun isSystemEnabled(systemKey: String): Boolean = systemKey !in disabledSystemKeys

    /** True when every scrapable system has been switched off. */
    fun hasAnyEnabledSystem(scrapableKeys: Collection<String>): Boolean =
        scrapableKeys.any { it !in disabledSystemKeys }
}

/** Persists settings in SharedPreferences and exposes them as observable state. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rgds_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun read(): AppSettings = AppSettings(
        libraryTreeUri = prefs.getString(KEY_TREE_URI, null),
        libraryLabel = prefs.getString(KEY_TREE_LABEL, null),
        devId = prefs.getString(KEY_DEV_ID, "").orEmpty(),
        devPassword = prefs.getString(KEY_DEV_PASSWORD, "").orEmpty(),
        userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
        userPassword = prefs.getString(KEY_USER_PASSWORD, "").orEmpty(),
        preferredRegion = prefs.getString(KEY_REGION, "wor").orEmpty().ifBlank { "wor" },
        forceExportFallback = prefs.getBoolean(KEY_FORCE_EXPORT, false),
        generateGamelist = prefs.getBoolean(KEY_GENERATE_GAMELIST, false),
        useHasheous = prefs.getBoolean(KEY_USE_HASHEOUS, true),
        theGamesDbApiKey = prefs.getString(KEY_TGDB_KEY, "").orEmpty(),
        useLibretroThumbnails = prefs.getBoolean(KEY_USE_LIBRETRO, true),
        detailedDiagnostics = prefs.getBoolean(KEY_DETAILED_DIAGNOSTICS, false),
        includeFileNamesInReport = prefs.getBoolean(KEY_REPORT_FILENAMES, false),
        // Copied out deliberately: SharedPreferences hands back its own live instance
        // and documents it as unsafe to keep or mutate, while AppSettings is a value
        // other threads read from a StateFlow.
        disabledSystemKeys = prefs.getStringSet(KEY_DISABLED_SYSTEMS, null)?.toSet().orEmpty(),
    )

    private fun write(update: AppSettings) {
        prefs.edit()
            .putString(KEY_TREE_URI, update.libraryTreeUri)
            .putString(KEY_TREE_LABEL, update.libraryLabel)
            .putString(KEY_DEV_ID, update.devId)
            .putString(KEY_DEV_PASSWORD, update.devPassword)
            .putString(KEY_USER_ID, update.userId)
            .putString(KEY_USER_PASSWORD, update.userPassword)
            .putString(KEY_REGION, update.preferredRegion)
            .putBoolean(KEY_FORCE_EXPORT, update.forceExportFallback)
            .putBoolean(KEY_GENERATE_GAMELIST, update.generateGamelist)
            .putBoolean(KEY_USE_HASHEOUS, update.useHasheous)
            .putString(KEY_TGDB_KEY, update.theGamesDbApiKey)
            .putBoolean(KEY_USE_LIBRETRO, update.useLibretroThumbnails)
            .putBoolean(KEY_DETAILED_DIAGNOSTICS, update.detailedDiagnostics)
            .putBoolean(KEY_REPORT_FILENAMES, update.includeFileNamesInReport)
            .putStringSet(KEY_DISABLED_SYSTEMS, update.disabledSystemKeys)
            .apply()
        _settings.value = update
    }

    fun setLibrary(treeUri: String, label: String) {
        write(current.copy(libraryTreeUri = treeUri, libraryLabel = label))
    }

    fun setCredentials(devId: String, devPassword: String, userId: String, userPassword: String) {
        write(
            current.copy(
                devId = devId.trim(),
                devPassword = devPassword.trim(),
                userId = userId.trim(),
                userPassword = userPassword.trim(),
            )
        )
    }

    fun setPreferredRegion(region: String) {
        write(current.copy(preferredRegion = region))
    }

    fun setForceExportFallback(enabled: Boolean) {
        write(current.copy(forceExportFallback = enabled))
    }

    fun setGenerateGamelist(enabled: Boolean) {
        write(current.copy(generateGamelist = enabled))
    }

    fun setUseHasheous(enabled: Boolean) {
        write(current.copy(useHasheous = enabled))
    }

    fun setTheGamesDbApiKey(apiKey: String) {
        write(current.copy(theGamesDbApiKey = apiKey.trim()))
    }

    fun setUseLibretroThumbnails(enabled: Boolean) {
        write(current.copy(useLibretroThumbnails = enabled))
    }

    fun setDetailedDiagnostics(enabled: Boolean) {
        write(current.copy(detailedDiagnostics = enabled))
    }

    fun setIncludeFileNamesInReport(enabled: Boolean) {
        write(current.copy(includeFileNamesInReport = enabled))
    }

    /**
     * Switches artwork lookup for one system on or off.
     *
     * Enabling removes the opt-out rather than recording an opt-in, so a system the
     * user never touched and one they deliberately turned back on are the same state.
     */
    fun setSystemEnabled(systemKey: String, enabled: Boolean) {
        val updated = if (enabled) {
            current.disabledSystemKeys - systemKey
        } else {
            current.disabledSystemKeys + systemKey
        }
        if (updated == current.disabledSystemKeys) return
        write(current.copy(disabledSystemKeys = updated))
    }

    /**
     * Switches every system in [systemKeys] on or off at once.
     *
     * Only the keys passed in are affected: turning everything off must not record an
     * opt-out for a system this release cannot scrape anyway, because that entry would
     * outlive the release that produced it and silently disable the system later.
     */
    fun setAllSystemsEnabled(systemKeys: Collection<String>, enabled: Boolean) {
        val updated = if (enabled) {
            current.disabledSystemKeys - systemKeys.toSet()
        } else {
            current.disabledSystemKeys + systemKeys
        }
        if (updated == current.disabledSystemKeys) return
        write(current.copy(disabledSystemKeys = updated))
    }

    private companion object {
        const val KEY_TREE_URI = "library_tree_uri"
        const val KEY_TREE_LABEL = "library_label"
        const val KEY_DEV_ID = "ss_dev_id"
        const val KEY_DEV_PASSWORD = "ss_dev_password"
        const val KEY_USER_ID = "ss_user_id"
        const val KEY_USER_PASSWORD = "ss_user_password"
        const val KEY_REGION = "preferred_region"
        const val KEY_FORCE_EXPORT = "force_export"
        const val KEY_GENERATE_GAMELIST = "generate_gamelist"
        const val KEY_USE_HASHEOUS = "use_hasheous"
        const val KEY_TGDB_KEY = "tgdb_api_key"
        const val KEY_USE_LIBRETRO = "use_libretro_thumbnails"
        const val KEY_DETAILED_DIAGNOSTICS = "detailed_diagnostics"
        const val KEY_REPORT_FILENAMES = "report_include_filenames"
        const val KEY_DISABLED_SYSTEMS = "disabled_system_keys"
    }
}
