package com.rork.rgdsartworkprep.provider

import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ProviderResult

/**
 * Everything a provider needs to identify one ROM, gathered once by the coordinator
 * so no provider has to read the file again.
 *
 * @param crc32 uppercase CRC32 hex, or null when the ROM was too large to hash or
 *   belongs to a disc system that is identified by name instead.
 */
data class RomIdentity(
    val fileName: String,
    val searchTitle: String,
    val sizeBytes: Long,
    val crc32: String?,
    val system: GameSystem,
)

/**
 * One source of game metadata and cover artwork.
 *
 * Implementations are deliberately interchangeable: the scraping pipeline only ever
 * talks to this interface, so a new source is added by writing one class and listing
 * it in [ProviderChain] — no change to the pipeline, the UI or the cache.
 *
 * Every method returns a [ProviderResult] rather than throwing, because a failing
 * lookup must never be able to stop a run.
 */
interface ArtworkProvider {

    val id: ProviderId

    /**
     * True when free-text search is cheap enough to run unattended for every
     * unmatched ROM in a batch.
     *
     * A source that has to spend several requests to answer one search is only
     * searched when the user asks for it by hand, so an overnight run of a thousand
     * ROMs can never turn into thousands of requests against a donated server.
     */
    val supportsBatchSearch: Boolean

    /** True when this provider can be used right now. */
    fun isConfigured(settings: AppSettings): Boolean

    /**
     * Called at the start of every batch, including an automatic retry pass.
     *
     * A source that paces or throttles itself uses this to forget how the previous
     * batch went. Health measured during one run must not decide how hard the next one
     * is allowed to try — otherwise a retry inherits the very handicap it exists to
     * overcome.
     */
    fun onRunStarted() {}

    /**
     * True while this source is failing or asking to be left alone.
     *
     * The pipeline uses it to tell a fact about a game ("no artwork exists") apart from
     * a fact about the moment ("the source was in no state to answer"), so the second
     * is never written down as the first.
     */
    val isDegraded: Boolean get() = false

    /**
     * Why it cannot be used, phrased for the user. Null when [isConfigured] is true.
     */
    fun unavailableReason(settings: AppSettings): String?

    /**
     * Identifies a ROM as precisely as this source allows — by checksum where it can,
     * by name where it cannot.
     */
    suspend fun identify(settings: AppSettings, rom: RomIdentity): ProviderResult<GameCandidate>

    /** Re-fetches a game the user (or a previous run) already settled on. */
    suspend fun gameById(
        settings: AppSettings,
        system: GameSystem,
        gameId: String,
    ): ProviderResult<GameCandidate>

    /** Free-text search, used by the manual match picker. */
    suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>>

    /** Downloads cover bytes for a URL this provider produced. */
    suspend fun downloadArtwork(url: String): ProviderResult<ByteArray>
}
