package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.GameSystem

/**
 * The key under which one ROM's confirmed identification is remembered.
 *
 * This key decides which artwork a ROM ends up with, so it must name *that file* and
 * nothing else. It previously did not.
 *
 * The old key was the checksum alone (`crc:<crc>`), on the reasoning that a checksum
 * identifies a game. It identifies *bytes*, which is not the same thing. Any two ROM
 * files holding identical bytes — the placeholder files a test library is built from,
 * a zero-byte stub, the same dump saved under two names — produced one shared key. The
 * first game identified wrote its match there and every other file with those bytes
 * read it back as its own, across systems, because the key did not mention the system
 * either. Choosing a cover by hand for one ROM then handed that cover to all of them:
 * the manual choice is remembered under the shared key, the next pass finds it for
 * every other ROM, and each one writes the resulting cover into its own correctly
 * named file. Five different games, five right filenames, one wrong picture in all of
 * them.
 *
 * So the checksum is now a *refinement* of the ROM's identity rather than the whole of
 * it: system, filename and size come first, and two distinct library entries can never
 * share a key no matter how identical their contents are. The cost of a key that is
 * too specific is one extra search; the cost of one that is too loose is silently
 * wrong artwork, which is the failure this exists to prevent.
 */
object RomIdentityKey {

    /**
     * Current key format. Entries written under an older format are unreadable by
     * design — see [MatchCacheRepository], which drops them rather than trusting a
     * match that may have been recorded against shared bytes.
     */
    const val PREFIX = "v2"

    /**
     * @param crc32 checksum of the ROM's contents, or null when it has none (see [usesChecksum])
     * @param systemKey [GameSystem.key] of the detected system, or null when undetected
     */
    fun of(crc32: String?, fileName: String, sizeBytes: Long, systemKey: String?): String {
        val scope = systemKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_SYSTEM
        val name = fileName.trim().lowercase()
        val fingerprint = crc32?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: NO_CHECKSUM
        return "$PREFIX:$scope:$name:$sizeBytes:$fingerprint"
    }

    /**
     * Whether this system's ROMs are identified by checksum.
     *
     * Disc images are gigabytes the databases do not index by hash, so they are
     * identified by name instead. Both the batch pipeline and the manual picker ask
     * this, because the two must derive the *same* key for the same ROM — when they
     * disagreed, a cover chosen by hand for a disc game was filed under a key nothing
     * ever read, and the choice was quietly forgotten on the next pass.
     */
    fun usesChecksum(system: GameSystem?): Boolean = system?.discBased != true

    private const val UNKNOWN_SYSTEM = "unknown"
    private const val NO_CHECKSUM = "nocrc"
}
