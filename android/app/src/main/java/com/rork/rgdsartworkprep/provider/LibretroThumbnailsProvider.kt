package com.rork.rgdsartworkprep.provider

import android.os.SystemClock
import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.data.RomNameNormalizer
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ApiDiagnostic
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import com.rork.rgdsartworkprep.network.RequestTimeout
import com.rork.rgdsartworkprep.provider.LibretroEntry as Entry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPath
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The libretro thumbnail archive — the community box-art collection RetroArch itself
 * downloads from. No account, no API key, no registration of any kind.
 *
 * This is an **artwork-only** source: there is no database behind it, just a very large
 * set of PNGs named after the games. It therefore sits last in the chain, where its job
 * is to find a cover for a ROM another source has already identified but has no picture
 * for. It contributes no description, publisher or genre, so the chain keeps the
 * metadata from whichever source recognised the game.
 *
 * Matching happens in two steps, cheapest first:
 * 1. The ROM's own filename is tried directly as a thumbnail name. Most libraries use
 *    No-Intro names, which is exactly how these files are named, so this usually hits
 *    with a single request and no parsing.
 * 2. If that misses, the system's index is fetched once and kept for the rest of the
 *    session, then matched on the normalised title. This is what rescues a ROM whose
 *    filename differs from the archive's — `Pinball (USA).nes` against the archive's
 *    `Pinball (Japan, USA) (En).png`, for instance.
 *
 * The index is a few megabytes for a large system, which is why it is only fetched
 * after the direct attempts fail, and only once per system per session.
 */
class LibretroThumbnailsProvider : ArtworkProvider {

    override val id: ProviderId = ProviderId.LibretroThumbnails

    /**
     * The index makes a search cheap only after it has been fetched, and fetching it is
     * megabytes. Identification already covers the unattended case, so the picker is
     * where the index is allowed to be paid for.
     */
    override val supportsBatchSearch: Boolean = false

    /**
     * Deliberately OkHttp rather than the Android engine the rest of the app uses.
     *
     * This is the smallest change that makes a stuck request actually stop. The
     * Android engine is built on `HttpURLConnection`, whose reads are blocking and
     * uninterruptible: coroutine cancellation cannot reach a thread parked inside
     * `InputStream.read`, and that engine's request timeout only narrows the *connect*
     * timeout, leaving an in-flight body unbounded. A PSX index fetch that trickled
     * bytes was therefore able to run for eleven minutes inside a four-minute job
     * budget — the queue asked it to stop and nothing was listening.
     *
     * OkHttp's [callTimeout] is a watchdog on another thread that bounds the whole
     * call — connect, send, and the entire body read — and enforces it by calling
     * `Call.cancel()`, which closes the socket underneath the blocked read. That is
     * what turns "the coroutine gave up" into "the request actually ended".
     *
     * Only this source is moved. Hasheous and ScreenScraper keep the engine and the
     * pacing they already had.
     */
    /**
     * Reads wire facts OkHttp hides from the caller. Never influences a request.
     *
     * Declared **before** [client] on purpose: Kotlin runs property initialisers in
     * declaration order, so a probe declared after the client it is installed into
     * would still be null when the engine is configured.
     */
    private val wireProbe = LibretroWireProbe()

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                readTimeout(SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                // The ceiling for one whole request. Everything above is per-hop and
                // can be satisfied forever by a server that keeps trickling bytes.
                callTimeout(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                // Observation only. Sits at the network layer because compression and
                // the negotiated encoding are both invisible from the caller's side.
                eventListener(wireProbe)
            }
        }
    }

    @Volatile
    private var requestObserver: ProviderRequestObserver? = null

    /**
     * Attaches the per-request recorder.
     *
     * Separate from the chain's [ProviderCallObserver] because that one times a whole
     * source call, and a whole source call here can be twelve requests. Which of them
     * was slow is exactly the question a single figure cannot answer.
     */
    fun setRequestObserver(observer: ProviderRequestObserver?) {
        requestObserver = observer
    }

    private val pacer = RequestPacer(TAG, MIN_INTERVAL_MILLIS)

    /**
     * One system's thumbnail names, kept for the session.
     *
     * A static file listing does not change during a scan, and re-fetching megabytes
     * per ROM would be indefensible, so this deliberately outlives a single run.
     */
    private val indexes = ConcurrentHashMap<String, List<Entry>>()

    /** Systems whose index could not be read, so it is not requested again and again. */
    private val unreadableIndexes = ConcurrentHashMap<String, Boolean>()

    /**
     * One lock per system, so a folder's index is fetched exactly once.
     *
     * The device report proved this was needed: two callers both passed the cache
     * check before either had stored a result, and the 9,339-entry PlayStation index
     * was downloaded and parsed twice concurrently for a single ROM. Caching alone
     * cannot prevent that — a cache is only consulted *before* the work, and the race
     * is entirely inside the window where the work is still running.
     *
     * A caller that arrives while a fetch is in flight waits for it and is then served
     * from the cache, which is strictly cheaper than the second download it replaces.
     * The scan is not the only caller: the manual picker calls [search] from the UI
     * while a scan is running, so this is load-bearing even now that the queue runs a
     * single loop.
     */
    private val indexLocks = ConcurrentHashMap<String, Mutex>()

    private val _lastDiagnostic = MutableStateFlow<ApiDiagnostic?>(null)

    val lastDiagnostic: StateFlow<ApiDiagnostic?> = _lastDiagnostic.asStateFlow()

    override fun onRunStarted() = pacer.onRunStarted()

    override val isDegraded: Boolean get() = pacer.isDegraded

    override fun isConfigured(settings: AppSettings): Boolean = settings.useLibretroThumbnails

    override fun unavailableReason(settings: AppSettings): String? =
        if (settings.useLibretroThumbnails) null else "Turned off in Settings"


    /**
     * An entry with its ranking factors already worked out.
     *
     * Ranking used to be expressed as a comparator that computed similarity inside
     * itself, so a sort re-derived the same Levenshtein distance for an entry every
     * time the entry was compared — O(n log n) evaluations of a cost that only varies
     * per entry. Measuring each entry once and then ordering the measurements is the
     * same ordering for a fraction of the work.
     */
    private class Ranked(
        val entry: Entry,
        val isTagged: Int,
        val regionRank: Int,
        val similarity: Float,
        val nameLength: Int,
    )

    override suspend fun identify(
        settings: AppSettings,
        rom: RomIdentity,
    ): ProviderResult<GameCandidate> {
        val folder = folderFor(rom.system)
            ?: return ProviderResult.Failure(ProviderError.NotFound)

        // The filename as-is, then with the tags libraries add and the archive does
        // not. One HEAD-shaped GET each, and most ROMs stop here.
        val base = rom.fileName.substringBeforeLast('.', rom.fileName)
        val guesses = directGuesses(base)
        guesses.forEachIndexed { position, guess ->
            val url = thumbnailUrl(folder, guess)
            // A shape, never the name itself: filenames are governed by the report's
            // own privacy setting and must not reach a trace by the back door.
            val target = "guess ${position + 1} of ${guesses.size}"
            when (val probe = exists(url, target)) {
                is ProviderResult.Success -> {
                    Log.i(TAG, "Matched \"$guess\" directly in $folder")
                    return ProviderResult.Success(candidate(guess, url, rom.system))
                }
                is ProviderResult.Failure -> {
                    // A request the app gave up on proves nothing about this name, and
                    // spending the same timeout again on the next guess is how one ROM
                    // used to consume the whole scan. Hand the slot back instead.
                    if (probe.error is ProviderError.Timeout) return probe
                }
            }
        }

        currentCoroutineContext().ensureActive()
        return when (val best = bestFromIndex(folder, rom.searchTitle, settings.preferredRegion)) {
            is ProviderResult.Failure -> best
            is ProviderResult.Success -> {
                val entry = best.value
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                Log.i(
                    TAG,
                    "Matched \"${rom.searchTitle}\" to \"${entry.name}\" via the $folder index",
                )
                ProviderResult.Success(
                    candidate(entry.name, thumbnailUrl(folder, entry.name), rom.system),
                )
            }
        }
    }

    /**
     * A remembered match is the thumbnail's own name, so re-fetching it is a single
     * request and can never drift onto a different game.
     */
    override suspend fun gameById(
        settings: AppSettings,
        system: GameSystem,
        gameId: String,
    ): ProviderResult<GameCandidate> {
        val name = gameId.removePrefix(NAME_ID_PREFIX).takeIf { gameId.startsWith(NAME_ID_PREFIX) }
            ?: return ProviderResult.Failure(ProviderError.NotFound)
        val folder = folderFor(system) ?: return ProviderResult.Failure(ProviderError.NotFound)
        val url = thumbnailUrl(folder, name)
        return when (val probe = exists(url, TARGET_REMEMBERED)) {
            is ProviderResult.Success -> ProviderResult.Success(candidate(name, url, system))
            is ProviderResult.Failure -> probe
        }
    }

    override suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProviderResult.Success(emptyList())
        // Without a system there is no index to search: the archive is organised by
        // platform and there are dozens of them.
        val target = system ?: return ProviderResult.Success(emptyList())
        val folder = folderFor(target) ?: return ProviderResult.Success(emptyList())

        val entries = when (val indexed = index(folder)) {
            // The picker is a person waiting on an answer: a source that could not be
            // read is reported, not quietly shown as "no results".
            is ProviderResult.Failure -> return if (indexed.error is ProviderError.Timeout) {
                indexed
            } else {
                ProviderResult.Success(emptyList())
            }
            is ProviderResult.Success -> indexed.value
        }
        val wanted = RomNameNormalizer.comparisonKey(trimmed)
        if (wanted.isEmpty()) return ProviderResult.Success(emptyList())

        val matches = entries.filter { it.key.contains(wanted) || wanted.contains(it.key) }
        val ranked = rank(matches, wanted, settings.preferredRegion)
            .sortedWith(PREFERENCE_ORDER)
            .take(MAX_SEARCH_RESULTS)
            .map { candidate(it.entry.name, thumbnailUrl(folder, it.entry.name), target) }
        return ProviderResult.Success(ranked)
    }

    override suspend fun downloadArtwork(url: String): ProviderResult<ByteArray> =
        pacer.run { attempt -> attemptDownload(url, attempt) }

    // region matching

    /**
     * Names worth trying before paying for the index.
     *
     * Region tags are the usual difference between a library's filename and the
     * archive's, so the common ones are tried explicitly rather than hoping the index
     * step catches everything.
     */
    private fun directGuesses(base: String): List<String> {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return emptyList()
        val withoutTags = trimmed.replace(Regex("\\s*\\[[^\\[\\]]*\\]"), "").trim()
        val bare = withoutTags.replace(Regex("\\s*\\([^()]*\\)"), "").trim()
        return listOf(trimmed, withoutTags, bare)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_DIRECT_GUESSES)
    }

    /**
     * The best thumbnail for a title, from the system's index.
     *
     * The archive keeps every dump of a game — translations, hacks, bad rips — so
     * picking the *first* name that matches would regularly attach a hacked ROM's box
     * art to a clean one. Candidates are ranked instead: clean dumps ahead of tagged
     * ones, the user's preferred region ahead of others, and the simplest name last as
     * a tie-break.
     */
    private suspend fun bestFromIndex(
        folder: String,
        searchTitle: String,
        preferredRegion: String,
    ): ProviderResult<Entry?> {
        val entries = when (val indexed = index(folder)) {
            is ProviderResult.Failure -> return indexed
            is ProviderResult.Success -> indexed.value
        }
        val wanted = RomNameNormalizer.comparisonKey(searchTitle)
        if (wanted.isEmpty()) return ProviderResult.Success(null)

        // Both passes compare already-normalised keys. Identical verdicts to comparing
        // the raw title against each entry's name — that is precisely what the old
        // call did internally — without re-normalising every entry for every ROM.
        val exact = entries.filter { it.key == wanted }
        val pool = exact.ifEmpty {
            // The length gate first. Measuring similarity runs a Levenshtein matrix,
            // and running one against all 9,339 PlayStation entries to find the single
            // game being looked for is almost entirely wasted: edit distance can never
            // be smaller than the difference in length, so an entry whose best possible
            // score falls short is rejected on arithmetic alone. Only candidates that
            // could still clear the bar are actually measured, so the verdict is
            // unchanged.
            entries.filter {
                RomNameNormalizer.similarityCanReach(wanted, it.key, CONFIDENT_SIMILARITY) &&
                    RomNameNormalizer.similarityOfKeys(wanted, it.key) >= CONFIDENT_SIMILARITY
            }
        }
        val best = rank(pool, wanted, preferredRegion).minWithOrNull(PREFERENCE_ORDER)?.entry
        return ProviderResult.Success(best)
    }

    /**
     * Measures each candidate once against the ranking rules: cleanest dump, preferred
     * region, closest name, simplest name. Used for both automatic matching and the
     * picker, so the order the user sees is the order the scan would have chosen.
     */
    private fun rank(
        entries: List<Entry>,
        wantedKey: String,
        preferredRegion: String,
    ): List<Ranked> {
        val regions = REGION_TOKENS[preferredRegion.lowercase(Locale.US)] ?: DEFAULT_REGIONS
        return entries.map { entry ->
            val regionIndex = regions.indexOfFirst { entry.name.contains(it, ignoreCase = true) }
            Ranked(
                entry = entry,
                isTagged = if (entry.name.contains('[')) 1 else 0,
                regionRank = if (regionIndex < 0) regions.size else regionIndex,
                similarity = RomNameNormalizer.similarityOfKeys(wantedKey, entry.key),
                nameLength = entry.name.length,
            )
        }
    }

    /**
     * The system's thumbnail names, fetched once per session.
     *
     * A system whose index cannot be read is remembered as such, so a scan does not
     * re-download megabytes for every remaining ROM to fail the same way.
     */
    private suspend fun index(folder: String): ProviderResult<List<Entry>> {
        cachedIndex(folder)?.let { return it }

        // Only one caller fetches a given system's index; the rest wait here and are
        // then served from the cache the winner filled. Holding this across the fetch
        // is what makes the double download impossible rather than merely unlikely.
        return lockFor(folder).withLock {
            cachedIndex(folder) ?: fetchIndex(folder)
        }
    }

    /** The settled answer for a folder, or null when it has not been decided yet. */
    private fun cachedIndex(folder: String): ProviderResult<List<Entry>>? {
        indexes[folder]?.let { return ProviderResult.Success(it) }
        if (unreadableIndexes.containsKey(folder)) {
            return ProviderResult.Failure(ProviderError.NotFound)
        }
        return null
    }

    private fun lockFor(folder: String): Mutex =
        indexLocks.getOrPut(folder) { Mutex() }

    /**
     * Downloads and parses one system's index, timing each phase separately.
     *
     * The phase split is the point. The previous build measured this whole span as one
     * number, so an index that reported 334.7 seconds against a 1.1-second download
     * could not say where the other 333 went — waiting for the request slot, or
     * parsing on the device's CPU. Those have opposite fixes, so the report now
     * states both.
     */
    private suspend fun fetchIndex(folder: String): ProviderResult<List<Entry>> {
        val url = "$BASE_URL/${folder.encodeURLPath()}/$BOXART_DIR/"
        val startedAt = SystemClock.elapsedRealtime()
        val result = pacer.run { attempt -> attemptText(url, attempt) }
        val fetchedAt = SystemClock.elapsedRealtime()
        val waitAndFetchMillis = fetchedAt - startedAt

        return when (result) {
            is ProviderResult.Failure -> {
                // A timeout is not a verdict on the index. Remembering the system as
                // unreadable here would make one slow moment permanent for the rest of
                // the session, so every later ROM on that system would be told its
                // covers do not exist — a wrong answer, cached.
                if (result.error is ProviderError.Timeout) {
                    Log.w(TAG, "Index for $folder timed out; leaving it to be retried")
                } else {
                    Log.i(TAG, "No index for $folder: ${result.error.userMessage}")
                    unreadableIndexes[folder] = true
                }
                recordIndex(
                    folder = folder,
                    entries = 0,
                    fetchMillis = waitAndFetchMillis,
                    parseMillis = 0L,
                    outcome = "Failure(${result.error.javaClass.simpleName})",
                )
                result
            }
            is ProviderResult.Success -> {
                val parsed = parseIndex(result.value)
                val parseMillis = SystemClock.elapsedRealtime() - fetchedAt
                if (parsed == null) {
                    // Parsing outlived its ceiling. Reported as a limit of this device
                    // rather than a timeout, because the two deserve opposite handling:
                    // a timeout is worth another attempt, while this device given this
                    // listing again will reach the same point again. On the RG DS that
                    // distinction was three attempts and 266 seconds spent re-proving
                    // one settled fact.
                    //
                    // Deliberately *not* remembered as unreadable: the archive answered
                    // correctly, so telling every later ROM on the system that its
                    // covers do not exist would cache a wrong answer.
                    Log.w(TAG, "Index for $folder exceeded this device's parse allowance")
                    recordIndex(folder, 0, waitAndFetchMillis, parseMillis, "ParseLimit")
                    ProviderResult.Failure(ProviderError.ProcessingLimit("index", parseMillis))
                } else if (parsed.isEmpty()) {
                    unreadableIndexes[folder] = true
                    // A listing that downloads fine and parses to nothing reads exactly
                    // like a fast success in a timings-only report. Said outright.
                    recordIndex(folder, 0, waitAndFetchMillis, parseMillis, "Empty")
                    ProviderResult.Failure(ProviderError.NotFound)
                } else {
                    Log.i(TAG, "Indexed ${parsed.size} covers for $folder")
                    indexes[folder] = parsed
                    recordIndex(folder, parsed.size, waitAndFetchMillis, parseMillis, "Success")
                    ProviderResult.Success(parsed)
                }
            }
        }
    }

    /**
     * Reads the PNG names out of a directory listing.
     *
     * Runs on the default dispatcher, not the caller's IO thread: this is the one
     * genuinely CPU-bound step in the provider, it walks several megabytes, and a
     * handheld's CPU is far slower than the desktop this was written on.
     *
     * Two properties matter as much as the speed. It is **cancellable** — the loop
     * checks the coroutine between entries, so a cancelled scan stops here instead of
     * running to completion on a dead job, which is how a parse outlived its own scan
     * and landed its result on the *next* ROM's record. And it is **bounded**, so a
     * pathologically large listing cannot hold the slot indefinitely.
     *
     * Only the names are kept — the markup around them is discarded immediately, so a
     * multi-megabyte page does not stay in memory as one.
     *
     * The reading itself is [LibretroIndexParser], which is plain Kotlin and therefore
     * testable on the JVM. This method keeps the policy around it: which dispatcher it
     * runs on, and how long it may take.
     *
     * @return the parsed entries, or null when parsing exceeded its ceiling.
     */
    private suspend fun parseIndex(html: String): List<Entry>? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(parseCeilingFor(html.length)) {
                LibretroIndexParser.parse(html)
            }
        }

    /**
     * How long this listing may take to parse.
     *
     * A flat 60-second ceiling was the direct cause of three ROMs failing on the
     * device: the PlayStation listing parsed in 65.0 seconds and was cut off, three
     * times, though nothing was wrong with it. Scaling the allowance with the size of
     * the page means a system cannot be punished for being large, while a
     * pathological input is still bounded.
     */
    private fun parseCeilingFor(htmlLength: Int): Long {
        val allowance = htmlLength.toLong() / BYTES_PER_CEILING_MILLI
        return (PARSE_CEILING_BASE_MILLIS + allowance).coerceAtMost(PARSE_CEILING_MAX_MILLIS)
    }

    private fun thumbnailUrl(folder: String, name: String): String =
        "$BASE_URL/${folder.encodeURLPath()}/$BOXART_DIR/${(name + PNG_SUFFIX).encodeURLPath()}"

    /**
     * An artwork-only candidate.
     *
     * The title is the thumbnail's own name rather than an invention of this app: when
     * this is the source that produced the cover, that name is the most accurate thing
     * known about the picture.
     */
    private fun candidate(name: String, url: String, system: GameSystem): GameCandidate =
        GameCandidate(
            gameId = NAME_ID_PREFIX + name,
            title = RomNameNormalizer.searchTitle(name),
            systemName = system.displayName,
            region = REGION_LABELS.firstOrNull { name.contains(it, ignoreCase = true) }.orEmpty(),
            coverUrl = url,
            provider = ProviderId.LibretroThumbnails,
        )

    // endregion

    // region requests

    /**
     * Whether one thumbnail name is present.
     *
     * Returns the whole result rather than a boolean so the caller can tell "the
     * archive says no" from "the archive never answered" — collapsing those two into
     * `false` is what let an abandoned request be reported as a missing cover.
     */
    private suspend fun exists(url: String, target: String): ProviderResult<Boolean> =
        pacer.run { attempt -> attemptExists(url, attempt, target) }

    private suspend fun attemptExists(
        url: String,
        attempt: Int,
        target: String,
    ): RequestPacer.Attempt<Boolean> {
        currentCoroutineContext().ensureActive()
        val startedAt = SystemClock.elapsedRealtime()
        wireProbe.reset()
        return try {
            val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
            val status = response.status.value
            val outcome = when {
                status in 200..299 -> "Present"
                status == 404 || status == 403 -> "Absent"
                else -> "Http($status)"
            }
            recordRequest(STAGE_GUESS, attempt, target, status, startedAt, outcome)
            when {
                status in 200..299 -> RequestPacer.Attempt.Done(true)
                status == 404 || status == 403 -> RequestPacer.Attempt.Fail(ProviderError.NotFound)
                status == 429 -> RequestPacer.Attempt.Retry(
                    ProviderError.RateLimited("archive is busy"),
                )
                status in 500..599 -> RequestPacer.Attempt.Retry(
                    ProviderError.Http(status, "the archive is having trouble"),
                )
                else -> RequestPacer.Attempt.Fail(ProviderError.NotFound)
            }
        } catch (cancellation: CancellationException) {
            recordRequest(STAGE_GUESS, attempt, target, 0, startedAt, "Cancelled")
            throw cancellation
        } catch (error: Throwable) {
            failureFor(error, "thumbnail check", startedAt, STAGE_GUESS, attempt, target)
        }
    }

    /**
     * Fetches one system's index. This is the request that stalled for eleven minutes,
     * so it is the one [CALL_TIMEOUT_MILLIS] exists for: a multi-megabyte listing
     * arriving a trickle at a time satisfies the per-read socket timeout indefinitely.
     */
    private suspend fun attemptText(url: String, attempt: Int): RequestPacer.Attempt<String> {
        currentCoroutineContext().ensureActive()
        val startedAt = SystemClock.elapsedRealtime()
        wireProbe.reset()
        return try {
            val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
            val status = response.status.value
            if (status in 200..299) {
                // Reading the body is where the time actually goes, so a failure here
                // is classified rather than flattened into an empty string: treating an
                // abandoned multi-megabyte read as "the index is empty" would mark the
                // whole system unreadable for the rest of the session.
                val text = try {
                    response.bodyAsText()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    return failureFor(
                        error,
                        "index download",
                        startedAt,
                        STAGE_INDEX,
                        attempt,
                        TARGET_INDEX,
                    )
                }
                recordRequest(
                    stage = STAGE_INDEX,
                    attempt = attempt,
                    target = TARGET_INDEX,
                    status = status,
                    startedAtMillis = startedAt,
                    outcome = if (text.isBlank()) "Blank" else "Read",
                    bodyBytes = text.length.toLong(),
                )
                if (text.isBlank()) {
                    RequestPacer.Attempt.Fail(ProviderError.NotFound)
                } else {
                    RequestPacer.Attempt.Done(text)
                }
            } else {
                record(url, status, "Index could not be listed")
                recordRequest(
                    STAGE_INDEX,
                    attempt,
                    TARGET_INDEX,
                    status,
                    startedAt,
                    "Http($status)",
                )
                when (status) {
                    429 -> RequestPacer.Attempt.Retry(ProviderError.RateLimited("archive is busy"))
                    in 500..599 -> RequestPacer.Attempt.Retry(
                        ProviderError.Http(status, "the archive is having trouble"),
                    )
                    else -> RequestPacer.Attempt.Fail(ProviderError.NotFound)
                }
            }
        } catch (cancellation: CancellationException) {
            recordRequest(STAGE_INDEX, attempt, TARGET_INDEX, 0, startedAt, "Cancelled")
            throw cancellation
        } catch (error: Throwable) {
            failureFor(error, "index download", startedAt, STAGE_INDEX, attempt, TARGET_INDEX)
        }
    }

    private suspend fun attemptDownload(url: String, attempt: Int): RequestPacer.Attempt<ByteArray> {
        currentCoroutineContext().ensureActive()
        val startedAt = SystemClock.elapsedRealtime()
        wireProbe.reset()
        return try {
            val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
            val status = response.status.value
            if (status in 200..299) {
                val bytes = response.readRawBytes()
                recordRequest(
                    stage = STAGE_COVER,
                    attempt = attempt,
                    target = TARGET_COVER,
                    status = status,
                    startedAtMillis = startedAt,
                    outcome = if (bytes.isEmpty()) "Empty" else "Downloaded",
                    bodyBytes = bytes.size.toLong(),
                )
                if (bytes.isEmpty()) {
                    record(url, status, "Cover was empty")
                    RequestPacer.Attempt.Fail(ProviderError.Http(status, "Cover came back empty"))
                } else {
                    RequestPacer.Attempt.Done(bytes)
                }
            } else {
                record(url, status, "Cover could not be downloaded")
                recordRequest(
                    STAGE_COVER,
                    attempt,
                    TARGET_COVER,
                    status,
                    startedAt,
                    "Http($status)",
                )
                when (status) {
                    404 -> RequestPacer.Attempt.Fail(ProviderError.NotFound)
                    429 -> RequestPacer.Attempt.Retry(ProviderError.RateLimited("archive is busy"))
                    in 500..599 -> RequestPacer.Attempt.Retry(
                        ProviderError.Http(status, "the archive is having trouble"),
                    )
                    else -> RequestPacer.Attempt.Fail(
                        ProviderError.Http(status, "Download failed"),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            recordRequest(STAGE_COVER, attempt, TARGET_COVER, 0, startedAt, "Cancelled")
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "Artwork download failed: ${error.javaClass.simpleName}")
            failureFor(error, "artwork download", startedAt, STAGE_COVER, attempt, TARGET_COVER)
        }
    }

    /**
     * Turns a thrown request failure into an attempt outcome.
     *
     * A timeout is reported as [ProviderError.Timeout] and, unlike other transient
     * failures, is **not** retried here. The pacer would otherwise spend the timeout
     * again two more times against the same unresponsive endpoint, which is the exact
     * monopolising of the single scraper slot this pass exists to stop. The queue
     * still retries it later, with backoff, after the ready work has drained.
     */
    private fun <T> failureFor(
        error: Throwable,
        stage: String,
        startedAtMillis: Long,
        traceStage: String,
        attempt: Int,
        target: String,
    ): RequestPacer.Attempt<T> {
        val elapsed = SystemClock.elapsedRealtime() - startedAtMillis
        val classified = RequestTimeout.classify(error, stage, elapsed)
        // A request that died is the most interesting one in the report, so it is
        // traced with the same detail as a successful one — including what it managed
        // to read before it stopped.
        recordRequest(
            stage = traceStage,
            attempt = attempt,
            target = target,
            status = 0,
            startedAtMillis = startedAtMillis,
            outcome = if (classified is ProviderError.Timeout) {
                "Timeout"
            } else {
                "Error(${error.javaClass.simpleName})"
            },
        )
        return if (classified is ProviderError.Timeout) {
            Log.w(TAG, "$stage gave up after ${elapsed}ms; handing the slot back")
            RequestPacer.Attempt.Fail(classified)
        } else {
            RequestPacer.Attempt.Retry(classified)
        }
    }

    /**
     * Writes one request down, with what the network layer saw.
     *
     * @param attempt arrives 0-based from the pacer and is published 1-based, because
     *   "attempt 1" is what a person reading a report expects the first try to be.
     */
    private fun recordRequest(
        stage: String,
        attempt: Int,
        target: String,
        status: Int,
        startedAtMillis: Long,
        outcome: String,
        bodyBytes: Long = UNKNOWN_BYTES,
    ) {
        val observer = requestObserver ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAtMillis
        val seen = wireProbe.take()
        // Diagnostics may never become a scanning failure.
        runCatching {
            observer.onProviderRequest(
                ProviderRequestTrace(
                    providerKey = id.key,
                    stage = stage,
                    attempt = attempt + 1,
                    method = seen.method,
                    target = target,
                    status = status,
                    elapsedMillis = elapsed,
                    wireBytes = seen.wireBytes,
                    declaredBytes = seen.declaredBytes,
                    bodyBytes = bodyBytes,
                    contentEncoding = seen.contentEncoding,
                    acceptEncoding = seen.acceptEncoding,
                    outcome = outcome,
                ),
            )
        }
    }

    private fun recordIndex(
        folder: String,
        entries: Int,
        fetchMillis: Long,
        parseMillis: Long,
        outcome: String,
    ) {
        val observer = requestObserver ?: return
        runCatching {
            observer.onProviderIndex(
                ProviderIndexTrace(
                    providerKey = id.key,
                    folder = folder,
                    entries = entries,
                    elapsedMillis = fetchMillis + parseMillis,
                    fetchMillis = fetchMillis,
                    parseMillis = parseMillis,
                    outcome = outcome,
                ),
            )
        }
    }

    /** Nothing is sent to this service but a URL, so there is nothing to withhold. */
    private fun record(url: String, status: Int, verdict: String) {
        _lastDiagnostic.value = ApiDiagnostic(
            provider = "Libretro thumbnails",
            endpoint = url.removePrefix(BASE_URL).take(MAX_DETAIL_LENGTH),
            httpStatus = status,
            serverMessage = "",
            sentFields = listOf("no credentials sent"),
            verdict = verdict,
        )
    }

    // endregion

    private companion object {
        const val TAG = "LibretroThumbnails"
        const val BASE_URL = "https://thumbnails.libretro.com"
        const val BOXART_DIR = "Named_Boxarts"
        const val USER_AGENT = "RGDSArtworkPrep/1.0"
        const val PNG_SUFFIX = ".png"

        /** Marks an id as "the thumbnail with this name". */
        const val NAME_ID_PREFIX = "name:"

        /** The attribute the scanner looks for, matched case-insensitively. */
        const val HREF_ATTRIBUTE = "href=\""

        /** Request kinds, as they appear in a diagnostic report. */
        const val STAGE_GUESS = "name guess"
        const val STAGE_INDEX = "index"
        const val STAGE_COVER = "cover"
        const val TARGET_INDEX = "system index"
        const val TARGET_COVER = "cover"
        const val TARGET_REMEMBERED = "remembered name"
        const val UNKNOWN_BYTES = -1L

        /** Cheap attempts before the index is downloaded. */
        const val MAX_DIRECT_GUESSES = 3
        const val MAX_SEARCH_RESULTS = 12

        /**
         * The floor of the parse allowance, before size is taken into account.
         *
         * Every ceiling here bounds pure CPU: the listing is already in memory, so
         * nothing the network does can influence it.
         */
        const val PARSE_CEILING_BASE_MILLIS = 30_000L

        /**
         * Additional allowance, as page bytes per extra millisecond.
         *
         * Calibrated from the device rather than chosen: the handheld parsed roughly
         * 100 KB/s of listing before this pass, so one millisecond per 100 bytes is
         * about ten times the measured worst case — ample for honest work of any size
         * even if the parser were no faster than before, while still finite.
         */
        const val BYTES_PER_CEILING_MILLI = 100L

        /** The absolute cap, past which an input is pathological rather than large. */
        const val PARSE_CEILING_MAX_MILLIS = 180_000L

        /**
         * Entries between cancellation checks while parsing.
         *
         * Frequent enough that a cancelled scan stops promptly, rare enough that the
         * check is not a measurable share of the parse itself.
         */
        const val CANCELLATION_CHECK_INTERVAL = 256

        /**
         * Ranking order, applied to already-measured candidates: clean dumps ahead of
         * tagged ones, preferred region next, then closest name, then simplest name.
         *
         * Stateless because every factor is computed before the sort, so one instance
         * serves every call instead of a comparator being rebuilt per lookup.
         */
        val PREFERENCE_ORDER: Comparator<Ranked> = compareBy<Ranked> { it.isTagged }
            .thenBy { it.regionRank }
            .thenByDescending { it.similarity }
            .thenBy { it.nameLength }

        /**
         * Deliberately high. This source supplies a picture with no metadata to check
         * it against, so a loose match would silently attach the wrong cover.
         */
        const val CONFIDENT_SIMILARITY = 0.93f

        /** Region tokens as the archive writes them, in the order each setting prefers. */
        val REGION_TOKENS: Map<String, List<String>> = mapOf(
            "wor" to listOf("(World)", "(USA", "(Europe", "(Japan"),
            "us" to listOf("(USA", "(World)", "(Europe", "(Japan"),
            "eu" to listOf("(Europe", "(World)", "(USA", "(Japan"),
            "jp" to listOf("(Japan", "(World)", "(USA", "(Europe"),
        )
        val DEFAULT_REGIONS = listOf("(World)", "(USA", "(Europe", "(Japan")
        val REGION_LABELS = listOf("World", "USA", "Europe", "Japan")

        const val MIN_INTERVAL_MILLIS = 250L
        const val CONNECT_TIMEOUT_MILLIS = 8_000L
        const val SOCKET_TIMEOUT_MILLIS = 60_000L

        /**
         * The ceiling for one complete request, enforced by OkHttp's call watchdog.
         *
         * Chosen from measurement, not preference. On the device, the slowest
         * *successful* index fetch observed was 290.5 seconds (Metroid Fusion, which
         * then matched correctly), while the stalled PSX index ran 661.6 and 723.1
         * seconds and ended in nothing either time. The value sits deliberately above
         * the slowest real success so a working-but-slow lookup still completes, and
         * well below the stalls so they are actually cut off.
         *
         * It is intentionally larger than the job budget: this bounds a *request*,
         * while the budget bounds a *job*, and a request that is still delivering a
         * result must be allowed to finish delivering it.
         */
        const val CALL_TIMEOUT_MILLIS = 420_000L
        const val MAX_DETAIL_LENGTH = 200
    }
}

/**
 * The archive's folder name for a system, or null when the archive has no folder for it.
 *
 * These are the libretro "system name" strings. They live on the catalog entry itself
 * because the same string has a second job there: recognising a RetroArch-shaped ROM
 * folder. Keeping one copy means the two uses can never drift apart.
 */
private fun folderFor(system: GameSystem): String? = system.libretroFolder
