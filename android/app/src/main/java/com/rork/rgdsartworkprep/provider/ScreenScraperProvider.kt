package com.rork.rgdsartworkprep.provider

import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ProviderResult
import com.rork.rgdsartworkprep.network.ScreenScraperClient

/**
 * Presents the existing ScreenScraper client through the common provider interface.
 *
 * This is a thin adapter on purpose: the client keeps its own credential checking,
 * quota tracking and diagnostics, all of which stay reachable from Settings. Nothing
 * about ScreenScraper's behaviour changes by being placed behind this seam — it
 * simply becomes one source among several instead of the only one.
 */
class ScreenScraperProvider(private val client: ScreenScraperClient) : ArtworkProvider {

    override val id: ProviderId = ProviderId.ScreenScraper

    /** One request per search, so it is safe to use unattended. */
    override val supportsBatchSearch: Boolean = true

    override fun isConfigured(settings: AppSettings): Boolean = settings.hasScreenScraperCredentials

    /** Both sources forget a bad batch the same way, so a retry is a real second chance. */
    override fun onRunStarted() = client.onRunStarted()

    override val isDegraded: Boolean get() = client.isDegraded

    override fun unavailableReason(settings: AppSettings): String? =
        if (settings.hasScreenScraperCredentials) null else "No developer credentials saved"

    override suspend fun identify(
        settings: AppSettings,
        rom: RomIdentity,
    ): ProviderResult<GameCandidate> = client.gameInfo(
        settings = settings,
        systemId = rom.system.screenScraperId,
        romFileName = rom.fileName,
        // Disc images are identified by name: a sheet file's own size describes the
        // sheet, not the game, so offering it would only mislead the match.
        romSize = if (rom.system.discBased) 0L else rom.sizeBytes,
        crc32 = rom.crc32,
    )

    override suspend fun gameById(
        settings: AppSettings,
        system: GameSystem,
        gameId: String,
    ): ProviderResult<GameCandidate> =
        client.gameById(settings, system.screenScraperId, gameId)

    override suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>> =
        client.search(settings, system?.screenScraperId, query)

    override suspend fun downloadArtwork(url: String): ProviderResult<ByteArray> =
        client.downloadArtwork(url)
}
