package com.rork.rgdsartworkprep.model

/**
 * The metadata sources the app can draw artwork from, in the order they are tried.
 *
 * Adding a source means adding a constant here and one implementation of
 * `ArtworkProvider` — nothing in the scraping pipeline needs to change.
 */
enum class ProviderId(
    /** Shown to the user. */
    val displayName: String,
    /** Where the artwork ultimately comes from, for on-screen credit. */
    val attribution: String,
    /** False when the user must supply credentials before it can be used. */
    val isCredentialFree: Boolean,
) {
    /**
     * Community hash-matching service. Identifies a ROM by checksum and serves the
     * matching cover itself, with no account or API key of any kind.
     */
    Hasheous(
        displayName = "Hasheous",
        attribution = "Hasheous, sourced from IGDB",
        isCredentialFree = true,
    ),

    /**
     * Community database with its own artwork CDN. Identifies by ROM hash as well as
     * by name, and needs a free API key the user requests for themselves.
     */
    TheGamesDb(
        displayName = "TheGamesDB",
        attribution = "TheGamesDB.net",
        isCredentialFree = false,
    ),

    /** Requires developer credentials the user registers for themselves. */
    ScreenScraper(
        displayName = "ScreenScraper",
        attribution = "ScreenScraper.fr",
        isCredentialFree = false,
    ),

    /**
     * The community box-art archive RetroArch downloads from. No account of any kind.
     *
     * Artwork only: there is no database behind it, so it can supply a cover but never
     * a description, publisher or genre. That is why it is tried last.
     */
    LibretroThumbnails(
        displayName = "Libretro thumbnails",
        attribution = "libretro thumbnail archive",
        isCredentialFree = true,
    );

    /**
     * True when this source only ever supplies a picture.
     *
     * The chain uses it to keep the metadata from whichever source recognised the game
     * instead of overwriting it with a name derived from an image filename.
     */
    val isArtworkOnly: Boolean get() = this == LibretroThumbnails

    /** Stable key used in stored data, so renaming a label never breaks the cache. */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): ProviderId? = entries.firstOrNull { it.key == key }
    }
}
