package com.rork.rgdsartworkprep

import android.app.Application
import android.content.Context
import com.rork.rgdsartworkprep.data.CrashLog
import com.rork.rgdsartworkprep.data.IgnoredFilesRepository
import com.rork.rgdsartworkprep.data.SharedPreferencesStringSetStore
import com.rork.rgdsartworkprep.data.LibraryStore
import com.rork.rgdsartworkprep.data.MatchCacheRepository
import com.rork.rgdsartworkprep.data.SafRomRepository
import com.rork.rgdsartworkprep.data.ScanStateStore
import com.rork.rgdsartworkprep.data.SettingsRepository
import com.rork.rgdsartworkprep.domain.ScrapeCoordinator
import com.rork.rgdsartworkprep.domain.diagnostics.ScanDiagnostics
import com.rork.rgdsartworkprep.service.ScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.rork.rgdsartworkprep.network.ScreenScraperClient
import com.rork.rgdsartworkprep.provider.HasheousProvider
import com.rork.rgdsartworkprep.provider.LibretroThumbnailsProvider
import com.rork.rgdsartworkprep.provider.ProviderChain
import com.rork.rgdsartworkprep.provider.ScreenScraperProvider
import com.rork.rgdsartworkprep.provider.TheGamesDbProvider

/** Process-wide singletons. Initialised once from [RgdsApplication]. */
object AppGraph {

    private lateinit var appContext: Context

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    /**
     * Filenames the user has asked never to see again. Stored in the app's private
     * data, so the list survives restarts and updates alike.
     */
    val ignoredFiles: IgnoredFilesRepository by lazy {
        IgnoredFilesRepository(
            SharedPreferencesStringSetStore(
                appContext.getSharedPreferences(IgnoredFilesRepository.PREFS, Context.MODE_PRIVATE),
            ),
        )
    }
    val saf: SafRomRepository by lazy { SafRomRepository(appContext) { ignoredFiles.current } }
    val matchCache: MatchCacheRepository by lazy { MatchCacheRepository(appContext) }
    /**
     * Kept as its own singleton because Settings talks to it directly for credential
     * checks, quota and diagnostics — none of which apply to a credential-free source.
     */
    val screenScraper: ScreenScraperClient by lazy { ScreenScraperClient() }

    /**
     * Held separately as well as in the chain so Settings can read its diagnostics.
     * It needs no credentials, so there is nothing else to configure here.
     */
    val hasheous: HasheousProvider by lazy { HasheousProvider() }

    /** Held separately so Settings can check the key and show its allowance. */
    val theGamesDb: TheGamesDbProvider by lazy { TheGamesDbProvider() }

    /** Held separately so Settings can show its diagnostics. Needs no credentials. */
    val libretroThumbnails: LibretroThumbnailsProvider by lazy { LibretroThumbnailsProvider() }

    /**
     * Artwork sources in the order they are tried.
     *
     * Sources that can identify a game *and* describe it come first, cheapest and
     * least restricted first: Hasheous needs nothing, TheGamesDB needs a free key,
     * ScreenScraper needs an account. The libretro archive is last because it is
     * artwork only — it has no metadata to offer, so it is the right place to look
     * once a game has been recognised but no cover has been found for it.
     */
    val providers: ProviderChain by lazy {
        ProviderChain(
            listOf(
                hasheous,
                theGamesDb,
                ScreenScraperProvider(screenScraper),
                libretroThumbnails,
            ),
        )
    }
    val library: LibraryStore by lazy { LibraryStore(saf, settings) }

    /** Survives the process being stopped, so an interrupted scan can be picked up. */
    val scanState: ScanStateStore by lazy { ScanStateStore(appContext) }

    /**
     * The app's own record of what a scan did.
     *
     * Attached to the provider chain as soon as it is built, so every source call is
     * timed from the one place they all pass through. Logcat is not enough on its own:
     * the device bug report taken from the RG DS had already lost those lines.
     */
    val scanDiagnostics: ScanDiagnostics by lazy {
        ScanDiagnostics().also { recorder ->
            recorder.isDetailedEnabled = settings.current.detailedDiagnostics
            providers.setCallObserver(recorder)
            // The chain times whole source calls; this times the individual requests
            // behind one. Attached directly to the libretro source because it is the
            // only one whose single call can be a dozen requests, and the only one
            // under investigation.
            libretroThumbnails.setRequestObserver(recorder)
        }
    }

    val scraper: ScrapeCoordinator by lazy {
        ScrapeCoordinator(
            context = appContext,
            saf = saf,
            settingsRepository = settings,
            matchCache = matchCache,
            providers = providers,
            stateStore = scanState,
            diagnostics = scanDiagnostics,
            ignoredFiles = { ignoredFiles.current },
        )
    }

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts the foreground service whenever a scan begins.
     *
     * Done here rather than at each call site so that every way of starting work — the
     * library screen, the file picker, a manual retry, a resume — is protected without
     * each one having to remember to do it.
     */
    fun observeScans() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            // A StateFlow already conflates repeats, so each transition arrives once.
            scraper.isScanning.collect { running ->
                if (running) ScanService.start(appContext)
            }
        }
        scope.launch {
            // One place applies the ignore list to what is already on screen, however
            // the list changed — the scan row's action or a name typed into Settings.
            ignoredFiles.ignored.collect { ignored ->
                library.applyIgnored(ignored)
                scraper.excludeIgnored(ignored)
            }
        }
    }
}

class RgdsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.install(this)
        installCrashRecorder()
        AppGraph.observeScans()
        // A scan interrupted by the process being stopped is picked up from its saved
        // snapshot, with completed artwork and attempt counts intact, rather than
        // starting over.
        AppGraph.scraper.resumeIfInterrupted()
    }

    /**
     * Writes any fatal error down before Android tears the process apart, so the next
     * launch can show exactly what happened in Settings. The platform handler still
     * runs afterwards — nothing is swallowed.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(this, "uncaught:${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
