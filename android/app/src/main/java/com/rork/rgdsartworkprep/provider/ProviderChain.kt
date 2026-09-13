package com.rork.rgdsartworkprep.provider

import android.os.SystemClock
import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.data.RomNameNormalizer
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.model.hasArtwork
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Somewhere to send per-call timings.
 *
 * Exists so the pipeline can be timed without any source knowing about it, and
 * without the chain depending on the diagnostics layer. Implementations must be
 * cheap and must never throw — this is called on the scanning path.
 */
interface ProviderCallObserver {
    fun onProviderCallStarted(providerKey: String, stage: String)
    fun onProviderCallFinished(
        providerKey: String,
        stage: String,
        elapsedMillis: Long,
        outcome: String,
    )
}

/**
 * A match the app has settled on before, and the source that produced it.
 *
 * The source is stored alongside the id because ids only mean something to the
 * service that issued them.
 */
data class RememberedMatch(val provider: ProviderId, val gameId: String) {

    fun serialize(): String = "${provider.key}$SEPARATOR$gameId"

    companion object {
        private const val SEPARATOR = "|"

        /** Entries saved before the app had more than one source are ScreenScraper's. */
        fun parse(raw: String?): RememberedMatch? {
            val text = raw?.takeIf { it.isNotBlank() } ?: return null
            val index = text.indexOf(SEPARATOR)
            if (index <= 0) return RememberedMatch(ProviderId.ScreenScraper, text)
            val provider = ProviderId.fromKey(text.substring(0, index)) ?: return null
            val id = text.substring(index + 1).takeIf { it.isNotBlank() } ?: return null
            return RememberedMatch(provider, id)
        }
    }
}

/** What the chain concluded about one ROM. */
sealed interface IdentifyOutcome {

    /** A single confident match. */
    data class Identified(val candidate: GameCandidate) : IdentifyOutcome

    /** Several plausible games — the user has to choose. */
    data class Ambiguous(val candidates: List<GameCandidate>, val best: GameCandidate) : IdentifyOutcome

    /** Every source was asked and none knew this ROM; it needs a manual match. */
    data object NoMatch : IdentifyOutcome

    /**
     * A source could not be asked properly — it was busy, timed out, or answered with
     * something unusable.
     *
     * This is deliberately not [NoMatch]. "Not in the database" is a fact about the
     * ROM and is worth remembering; "the service did not answer" is a fact about the
     * moment, and telling the user their game does not exist because a server was busy
     * would send them hunting for a match that was there all along. Retrying is
     * worthwhile, so it is reported as a failure rather than a verdict.
     */
    data class Unresolved(val error: ProviderError) : IdentifyOutcome

    /** No source is usable any more, so continuing the batch is pointless. */
    data class NoProviderLeft(val error: ProviderError) : IdentifyOutcome
}

/**
 * Tries each artwork source in turn until one identifies the ROM.
 *
 * The order is fixed and meaningful: credential-free sources come first, so the app
 * works out of the box, and sources that need an account are only reached when the
 * free ones came up empty.
 *
 * A source that refuses everything — rejected credentials, exhausted quota, a closed
 * service — is set aside for the rest of the run rather than being asked again for
 * every remaining ROM. The run itself only stops once nothing is left to ask, which
 * is why a broken ScreenScraper login can no longer end a batch that Hasheous is
 * perfectly able to finish.
 */
class ProviderChain(private val providers: List<ArtworkProvider>) {

    /** Sources set aside for this run, with the refusal that retired them. */
    private val setAside = ConcurrentHashMap<ProviderId, ProviderError>()

    /**
     * Where call timings go. Null until diagnostics attach one, so the chain works
     * exactly as before when nothing is listening.
     */
    @Volatile
    private var observer: ProviderCallObserver? = null

    fun setCallObserver(callObserver: ProviderCallObserver?) {
        observer = callObserver
    }

    /** Clears the per-run state. Called whenever a new batch begins. */
    fun beginRun() {
        setAside.clear()
        // Sources that pace themselves start each batch with a clean slate, so a retry
        // is a genuine second chance rather than a continuation of a bad run.
        providers.forEach { it.onRunStarted() }
    }

    /** True when the named source is currently failing or throttling itself. */
    fun isDegraded(id: ProviderId): Boolean =
        providers.firstOrNull { it.id == id }?.isDegraded == true

    /** Sources the user has configured, in priority order. */
    fun configured(settings: AppSettings): List<ArtworkProvider> =
        providers.filter { it.isConfigured(settings) }

    /** Configured sources that have not refused everything yet. */
    fun usable(settings: AppSettings): List<ArtworkProvider> =
        configured(settings).filter { it.id !in setAside.keys }

    fun isConfiguredAtAll(settings: AppSettings): Boolean = configured(settings).isNotEmpty()

    /**
     * Identifies one ROM.
     *
     * A remembered match is honoured first, then each source is asked to identify the
     * ROM exactly, then — only for sources where a search is a single cheap request —
     * by name.
     *
     * Identifying a ROM and finding its artwork are not the same achievement. A source
     * may know a game and have no cover mapped to it, so a match without artwork does
     * not end the search: the remaining sources are still asked, and the coverless
     * record is kept only as a last resort for its title and metadata. Letting such a
     * match win outright meant a hash-matching source with no picture could shut out a
     * source that had one.
     */
    suspend fun identify(
        settings: AppSettings,
        rom: RomIdentity,
        remembered: RememberedMatch?,
    ): IdentifyOutcome {
        val available = usable(settings)
        if (available.isEmpty()) {
            return IdentifyOutcome.NoProviderLeft(
                setAside.values.firstOrNull() ?: ProviderError.MissingCredentials,
            )
        }

        // The best "close but not certain" list seen so far, kept in case no source
        // manages a confident match and the user has to pick.
        var ambiguous: IdentifyOutcome.Ambiguous? = null

        // A source that could not answer at all. Held separately from a source that
        // answered "unknown", so the two are never reported as the same thing.
        var unanswered: ProviderError? = null

        // A source whose reply arrived but could not be processed on this device. Kept
        // apart from [unanswered] because it is not transient: another attempt reaches
        // the same point. It still may not be reported as "no match" — the cover was
        // never actually looked for, so saying the game has none would be an invention.
        var unprocessable: ProviderError? = null

        // A confident match that arrived with no artwork. Worth keeping for its title,
        // never worth stopping for while a source that might have a cover is unasked.
        var coverless: GameCandidate? = null

        remembered?.let { match ->
            val owner = available.firstOrNull { it.id == match.provider }
            if (owner != null) {
                when (val result = guard(owner, "lookup") { owner.gameById(settings, rom.system, match.gameId) }) {
                    is ProviderResult.Success -> {
                        // A remembered match is re-checked for artwork like any other.
                        // Matches are remembered as soon as a game is identified, so a
                        // coverless one would otherwise keep winning on every later run
                        // and permanently hide a source that does have the cover.
                        if (result.value.hasArtwork) return IdentifyOutcome.Identified(result.value)
                        coverless = result.value
                    }
                    is ProviderResult.Failure -> noteFailure(owner, result.error)
                }
            }
        }

        for (provider in available) {
            if (provider.id in setAside.keys) continue

            var knewItWithoutArtwork = false
            when (val exact = guard(provider, "identify") { provider.identify(settings, rom) }) {
                is ProviderResult.Success -> {
                    val candidate = exact.value
                    if (candidate.hasArtwork) {
                        return IdentifyOutcome.Identified(candidate.enrichedWith(coverless))
                    }
                    if (coverless == null) coverless = candidate
                    knewItWithoutArtwork = true
                    Log.i(
                        TAG,
                        "${provider.id.displayName} knows this ROM but has no cover for it; " +
                            "trying the remaining sources",
                    )
                }
                is ProviderResult.Failure -> {
                    noteFailure(provider, exact.error)
                    unanswered = unanswered ?: exact.error.takeIf { it.isTransient }
                    if (exact.error is ProviderError.ProcessingLimit && unprocessable == null) {
                        unprocessable = exact.error
                    }
                }
            }

            // A source that identified the ROM has already given its best answer for
            // it; asking the same source again by name would only spend another request
            // against its quota to be told about the same artwork-free record.
            if (knewItWithoutArtwork) continue

            if (!provider.supportsBatchSearch || provider.id in setAside.keys) continue

            when (
                val results =
                    guard(provider, "search") { provider.search(settings, rom.system, rom.searchTitle) }
            ) {
                is ProviderResult.Failure -> {
                    noteFailure(provider, results.error)
                    unanswered = unanswered ?: results.error.takeIf { it.isTransient }
                    if (results.error is ProviderError.ProcessingLimit && unprocessable == null) {
                        unprocessable = results.error
                    }
                }
                is ProviderResult.Success -> {
                    when (val decision = judge(rom.searchTitle, results.value)) {
                        is IdentifyOutcome.Identified -> {
                            val found = decision.candidate
                            if (found.hasArtwork) {
                                return IdentifyOutcome.Identified(found.enrichedWith(coverless))
                            }
                            if (coverless == null) coverless = found
                        }
                        is IdentifyOutcome.Ambiguous -> if (ambiguous == null) ambiguous = decision
                        else -> Unit
                    }
                }
            }
        }

        // A list for the user to choose from beats a match with no picture: one of those
        // candidates may well carry the cover this ROM needs.
        ambiguous?.let { return it }

        if (usable(settings).isEmpty()) {
            return IdentifyOutcome.NoProviderLeft(
                setAside.values.firstOrNull() ?: ProviderError.NotFound,
            )
        }
        // A source that never answered may have been the one holding the artwork, so
        // that is reported as a failed request rather than as a game without a cover.
        unanswered?.let { return IdentifyOutcome.Unresolved(it) }
        // Same reasoning, different cure: the reply arrived and this device could not
        // work through it. Reported rather than retried, and never as a missing game.
        unprocessable?.let { return IdentifyOutcome.Unresolved(it) }
        // Everything was asked and everything answered. Only now is a coverless match
        // the whole truth about this ROM.
        coverless?.let { return IdentifyOutcome.Identified(it) }
        return IdentifyOutcome.NoMatch
    }

    /**
     * Puts the artwork of one match together with the description of another.
     *
     * The last source in the chain supplies covers but no metadata, so without this a
     * ROM that Hasheous recognised by checksum would end up labelled from an image
     * filename, losing the title and release date it already had. The artwork-bearing
     * candidate stays in charge of its own id, provider and cover — that is what routes
     * the download and what gets remembered — and only fields it has nothing for are
     * filled in from the match that came before it.
     *
     * The two are only combined when they clearly refer to the same game. A picture
     * matched by name and a record matched by checksum can disagree, and inheriting a
     * description from the wrong game would be worse than having none.
     */
    private fun GameCandidate.enrichedWith(fallback: GameCandidate?): GameCandidate {
        val other = fallback ?: return this
        if (!provider.isArtworkOnly) return this
        if (RomNameNormalizer.similarity(title, other.title) < SAME_GAME_THRESHOLD) {
            Log.i(
                TAG,
                "Kept the cover from ${provider.displayName} but not " +
                    "${other.provider.displayName}'s details: they may be different games",
            )
            return this
        }
        return copy(
            // The source that identified the game names it better than an image file does.
            title = other.title.ifBlank { title },
            systemName = systemName.ifBlank { other.systemName },
            region = region.ifBlank { other.region },
            description = description ?: other.description,
            developer = developer ?: other.developer,
            publisher = publisher ?: other.publisher,
            genre = genre ?: other.genre,
            players = players ?: other.players,
            releaseDate = releaseDate ?: other.releaseDate,
            rating = rating ?: other.rating,
        )
    }

    /**
     * Decides whether a result list is good enough to accept without asking.
     *
     * A match is only taken automatically when it is both close to the filename and
     * clearly ahead of the runner-up, so near-identical sequels are never picked for
     * the user.
     */
    private fun judge(searchTitle: String, results: List<GameCandidate>): IdentifyOutcome {
        val best = results.firstOrNull() ?: return IdentifyOutcome.NoMatch
        val confidence = RomNameNormalizer.similarity(searchTitle, best.title)
        val runnerUp = results.getOrNull(1)
            ?.let { RomNameNormalizer.similarity(searchTitle, it.title) } ?: 0f
        return if (confidence >= CONFIDENT_THRESHOLD && confidence - runnerUp >= RUNNER_UP_LEAD) {
            IdentifyOutcome.Identified(best)
        } else {
            IdentifyOutcome.Ambiguous(results, best)
        }
    }

    /**
     * Free-text search for the manual picker.
     *
     * Every configured source is asked, including ones whose search is too expensive
     * for an unattended batch, because here the user is waiting for an answer and one
     * search is worth the requests.
     */
    suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>> {
        val available = configured(settings)
        if (available.isEmpty()) return ProviderResult.Failure(ProviderError.MissingCredentials)

        var lastError: ProviderError? = null
        available.forEach { provider ->
            when (val result = guard(provider, "search") { provider.search(settings, system, query) }) {
                is ProviderResult.Success -> if (result.value.isNotEmpty()) return result
                is ProviderResult.Failure -> lastError = result.error
            }
        }
        return lastError?.let { ProviderResult.Failure(it) } ?: ProviderResult.Success(emptyList())
    }

    /** Downloads a cover through the source that offered it. */
    suspend fun downloadArtwork(candidate: GameCandidate): ProviderResult<ByteArray> {
        val url = candidate.coverUrl
        if (url.isNullOrBlank()) return ProviderResult.Failure(ProviderError.NotFound)
        val owner = providers.firstOrNull { it.id == candidate.provider }
            ?: return ProviderResult.Failure(ProviderError.NotFound)
        return guard(owner, "artwork download") { owner.downloadArtwork(url) }
    }

    /**
     * The boundary between a source and the pipeline.
     *
     * Providers are contracted to report failures rather than throw, but a contract is
     * not a guarantee: a reply in an unforeseen shape, or a bug in the code that reads
     * one, would otherwise escape as an exception and be reported as a nameless error
     * with nothing to act on. Everything is caught here and given a name, so a single
     * bad ROM is always just a failed row and the batch keeps going.
     */
    private suspend fun <T> guard(
        provider: ArtworkProvider,
        stage: String,
        call: suspend () -> ProviderResult<T>,
    ): ProviderResult<T> {
        val startedAtMillis = SystemClock.elapsedRealtime()
        notifyStarted(provider, stage)
        return try {
            val result = call()
            logTiming(provider, stage, startedAtMillis, describeOutcome(result))
            result
        } catch (cancellation: CancellationException) {
            logTiming(provider, stage, startedAtMillis, "Cancelled")
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "${provider.id.displayName} $stage threw ${error.javaClass.simpleName}")
            logTiming(provider, stage, startedAtMillis, "Threw(${error.javaClass.simpleName})")
            val detail = error.javaClass.simpleName +
                (error.message?.take(MAX_DETAIL_LENGTH)?.let { ": $it" } ?: "")
            ProviderResult.Failure(
                ProviderError.Unexpected("${provider.id.displayName} $stage", detail),
            )
        }
    }

    /**
     * How long one source call took.
     *
     * Every call to every source already passes through [guard], so measuring here
     * attributes wall-clock time to a specific source and stage without any source
     * having to know it is being timed. Purely observational — nothing in the app
     * reads this back, and the value never influences a decision.
     */
    private fun logTiming(
        provider: ArtworkProvider,
        stage: String,
        startedAtMillis: Long,
        outcome: String,
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMillis
        Log.i(
            TAG,
            "$TIMING_MARKER provider=${provider.id.key} stage=$stage " +
                "elapsedMs=$elapsedMs outcome=$outcome",
        )
        // The in-app record is what a user can actually send back; logcat had already
        // rotated these away by the time the device bug report was taken.
        runCatching { observer?.onProviderCallFinished(provider.id.key, stage, elapsedMs, outcome) }
    }

    /** Never lets a diagnostics failure become a scanning failure. */
    private fun notifyStarted(provider: ArtworkProvider, stage: String) {
        runCatching { observer?.onProviderCallStarted(provider.id.key, stage) }
    }

    private fun <T> describeOutcome(result: ProviderResult<T>): String = when (result) {
        is ProviderResult.Success -> "Success"
        is ProviderResult.Failure -> "Failure(${result.error.javaClass.simpleName})"
    }

    /**
     * Retires a source that cannot serve anyone until the user acts. Ordinary misses
     * are ignored — only a blanket refusal takes a source out of the run.
     */
    private fun noteFailure(provider: ArtworkProvider, error: ProviderError) {
        if (!error.stopsRun) return
        if (setAside.putIfAbsent(provider.id, error) == null) {
            Log.w(TAG, "${provider.id.displayName} set aside for this run: ${error.javaClass.simpleName}")
        }
    }

    /**
     * A sentence for the user when a source dropped out but the run carried on, so a
     * silently degraded batch never looks like a healthy one.
     */
    fun setAsideNote(settings: AppSettings): String? {
        if (setAside.isEmpty()) return null
        val remaining = usable(settings)
        if (remaining.isEmpty()) return null
        val retired = setAside.entries.joinToString(", ") { (id, error) ->
            "${id.displayName} (${error.userMessage})"
        }
        val carrying = remaining.joinToString(", ") { it.id.displayName }
        return "Stopped using $retired. Continuing with $carrying."
    }

    private companion object {
        const val TAG = "ProviderChain"

        /**
         * One grep-able marker on every timing line, so a controlled test run can be
         * filtered out of the log without reading around it.
         */
        const val TIMING_MARKER = "TIMING"
        const val CONFIDENT_THRESHOLD = 0.86f
        const val RUNNER_UP_LEAD = 0.08f

        /**
         * How alike two titles must be before one match's details are attached to
         * another's picture. Set high: the cost of being wrong here is a game
         * described as an entirely different one.
         */
        const val SAME_GAME_THRESHOLD = 0.9f
        const val MAX_DETAIL_LENGTH = 200
    }
}
