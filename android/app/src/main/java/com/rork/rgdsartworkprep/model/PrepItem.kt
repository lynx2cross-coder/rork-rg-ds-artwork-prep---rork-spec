package com.rork.rgdsartworkprep.model

/** Per-game outcome of an artwork preparation run. */
enum class PrepStatus {
    Pending,
    Working,
    Downloaded,
    Exported,
    AlreadyExists,
    MultipleMatches,

    /**
     * Automatic matching could not decide, but the game can still be found by hand.
     *
     * Split out from [NotFound] after device testing: a row the user could tap and
     * immediately resolve was labelled "Not found" in red, which says the search is
     * over and the artwork does not exist. Both were untrue — no usable automatic
     * match was reached, and a manual search generally returns exactly the game.
     */
    ChooseArtwork,

    /** An actual search ran and produced nothing usable. */
    NotFound,
    ApiError,
    Unsupported,

    /**
     * The user switched this system off, so no artwork was looked up.
     *
     * Kept apart from [Unsupported] because they are opposite facts. "Unsupported"
     * says the app cannot look this system up; this says it can, and was told not to.
     * Reusing the former would label a deliberate choice as a limitation and send the
     * user looking for a defect that does not exist — and unlike every other skip, the
     * remedy is a switch they own.
     */
    SystemDisabled,
}

/**
 * How a status should read to the user, independent of the palette.
 *
 * Colour is a promise. Red says something went wrong and is worth investigating;
 * spending it on a row that merely needs a person to pick from a list teaches the
 * user to ignore red, which is precisely when a real failure slips past. Keeping the
 * mapping here rather than inside the composables means the rule can be asserted in a
 * plain unit test, and that the chip and the filter chips cannot drift apart.
 */
enum class StatusTone { Neutral, Progress, Positive, Attention, Failure }

val PrepStatus.tone: StatusTone
    get() = when (this) {
        PrepStatus.Pending -> StatusTone.Neutral
        PrepStatus.Working -> StatusTone.Progress
        PrepStatus.Downloaded, PrepStatus.Exported -> StatusTone.Positive
        PrepStatus.AlreadyExists,
        PrepStatus.Unsupported,
        PrepStatus.SystemDisabled,
        -> StatusTone.Neutral
        PrepStatus.MultipleMatches, PrepStatus.ChooseArtwork -> StatusTone.Attention
        PrepStatus.NotFound, PrepStatus.ApiError -> StatusTone.Failure
    }

/**
 * Tapping a row in this state opens the manual search sheet.
 *
 * The list's click handler and the row's own clickable modifier both need this, and
 * they used to spell it out separately. A row that looks tappable but is not — or the
 * reverse — is a bug the user meets long before a test does.
 */
val PrepStatus.opensManualSearch: Boolean
    get() = this == PrepStatus.MultipleMatches ||
        this == PrepStatus.ChooseArtwork ||
        this == PrepStatus.NotFound

val PrepStatus.isTerminalFailure: Boolean
    get() = this == PrepStatus.NotFound || this == PrepStatus.ApiError

/**
 * Worth another automatic attempt.
 *
 * [PrepStatus.ChooseArtwork] is included because these rows were [PrepStatus.NotFound]
 * before the status was split, and "Retry failed" has always requeued them. Excluding
 * them would look tidier — automatic matching has had its turn — but it would remove
 * a button that works today, and a retry is a genuine second chance here: a source
 * that was rate-limited or missing credentials during the run may answer this time.
 * The colour of a row is a presentation decision; what the queue does with it is not.
 */
val PrepStatus.isRetryable: Boolean
    get() = this == PrepStatus.ApiError ||
        this == PrepStatus.NotFound ||
        this == PrepStatus.ChooseArtwork

/** Still queued or in flight — nothing has been decided for this game yet. */
val PrepStatus.isUnfinished: Boolean
    get() = this == PrepStatus.Pending || this == PrepStatus.Working

/**
 * Wording for the "Retry …" button when a status filter is active.
 *
 * The button lowercases and interpolates this, and [PrepStatus.ChooseArtwork]'s own
 * label would read "Retry choose artwork" — an instruction, not a description of what
 * is being retried.
 */
val PrepStatus.retryLabel: String
    get() = if (this == PrepStatus.ChooseArtwork) "unmatched" else label

val PrepStatus.label: String
    get() = when (this) {
        PrepStatus.Pending -> "Waiting"
        PrepStatus.Working -> "Working…"
        PrepStatus.Downloaded -> "Downloaded"
        PrepStatus.Exported -> "Exported"
        PrepStatus.AlreadyExists -> "Already exists"
        PrepStatus.MultipleMatches -> "Multiple matches"
        PrepStatus.ChooseArtwork -> "Choose artwork"
        PrepStatus.NotFound -> "Not found"
        PrepStatus.ApiError -> "API error"
        PrepStatus.Unsupported -> "Unsupported"
        PrepStatus.SystemDisabled -> "System off"
    }

/**
 * A candidate game returned by a metadata provider.
 *
 * The descriptive fields are only used when gamelist.xml generation is switched on;
 * artwork preparation itself needs nothing beyond [coverUrl].
 *
 * [provider] records where the entry came from, so the cover is downloaded through
 * the same service that offered it and a remembered match is looked up again there.
 */
data class GameCandidate(
    val gameId: String,
    val title: String,
    val systemName: String,
    val region: String,
    val coverUrl: String?,
    val provider: ProviderId = ProviderId.ScreenScraper,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    /** EmulationStation format: `yyyyMMddT000000`. */
    val releaseDate: String? = null,
    /** EmulationStation format: `0.00`–`1.00`. */
    val rating: String? = null,
)

/**
 * True when this candidate actually carries a cover the app can download.
 *
 * A source can know a game perfectly well and still have no artwork mapped to it, so
 * being identified and being usable for artwork are two different achievements.
 */
val GameCandidate.hasArtwork: Boolean get() = !coverUrl.isNullOrBlank()

/** One ROM inside a preparation run, with its live status. */
data class PrepItem(
    val rom: RomEntry,
    val status: PrepStatus = PrepStatus.Pending,
    val resolvedTitle: String? = null,
    val message: String? = null,
    val candidates: List<GameCandidate> = emptyList(),
    val savedPath: String? = null,
    /** Provider release date in EmulationStation format `yyyyMMddT000000`, when known. */
    val releaseDate: String? = null,
) {
    val id: String get() = rom.id

    /** Release year parsed from [releaseDate]; null while the game is unresolved. */
    val releaseYear: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}
