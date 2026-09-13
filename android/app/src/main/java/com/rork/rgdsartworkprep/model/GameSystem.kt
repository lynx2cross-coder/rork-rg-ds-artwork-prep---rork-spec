package com.rork.rgdsartworkprep.model

/**
 * One entry of the maintainable system catalog.
 *
 * @param key stable internal identifier
 * @param displayName human readable console name
 * @param shortName compact tag shown in dense lists
 * @param screenScraperId ScreenScraper `systemeid`
 * @param extensions unambiguous file extensions that alone identify this system
 * @param folderAliases lowercase folder names commonly used for this system
 * @param scrapingEnabled whether artwork lookup is enabled in this release
 * @param discBased true for CD/DVD systems whose games are disc images, often split into
 *   a sheet plus track files, stored in a per-game folder, or spread over several discs
 * @param libretroFolder exact platform folder name used by the libretro thumbnail archive,
 *   which is also the folder name RetroArch itself writes on disk. Serves two purposes:
 *   it addresses the archive, and it doubles as a lower-priority folder alias so a
 *   RetroArch-shaped library is recognised without hand-listing each name twice.
 */
data class GameSystem(
    val key: String,
    val displayName: String,
    val shortName: String,
    val screenScraperId: Int,
    val extensions: Set<String>,
    val folderAliases: Set<String>,
    val scrapingEnabled: Boolean,
    val discBased: Boolean = false,
    val libretroFolder: String? = null,
)

/**
 * Catalog of supported systems plus the detection rules that map a ROM file to one.
 *
 * Ambiguous containers (`bin`, `cue`, `iso`, `zip`, `chd`, ...) are never resolved by
 * extension alone — the folder the ROM lives in decides the system.
 *
 * Folder matching is exact after normalization — never fuzzy, never substring. A folder
 * called `Model 2` stays unknown rather than being guessed at from a `Sega` folder above
 * it, because a wrong system is worse than no system: it sends the ROM to the wrong
 * platform on every provider.
 */
object SystemCatalog {

    /**
     * Extensions that several systems share; folder context is required for these.
     *
     * `rvz` is Dolphin's container and is written for both GameCube and Wii, so it
     * belongs here rather than to either system: deciding it by extension would send
     * half of a Dolphin library to the wrong platform on every provider.
     */
    val ambiguousExtensions: Set<String> = setOf(
        "bin", "cue", "iso", "zip", "7z", "chd", "img", "ccd", "sub", "m3u", "pbp", "rar",
        "raw", "mds", "mdf", "nrg", "toc", "ecm", "rvz",
    )

    /** Extensions that are artwork rather than ROMs. */
    val imageExtensions: Set<String> = setOf("jpg", "jpeg", "png")

    /** Files that describe a disc but are not the game itself. */
    private val sheetExtensions: Set<String> = setOf("cue", "gdi", "ccd")

    /** Multi-disc playlists — the playlist is the game, its discs are hidden. */
    private val playlistExtensions: Set<String> = setOf("m3u")

    /** Never a game on their own: they only exist to support a sheet. */
    private val sidecarExtensions: Set<String> = setOf("sub", "mds", "toc", "ecm")

    /** Raw disc payloads, hidden when a sheet of the same name is present. */
    private val payloadExtensions: Set<String> = setOf("bin", "img", "iso", "raw", "mdf")

    /** Every extension that can take part in a disc set. */
    private val discExtensions: Set<String> =
        sheetExtensions + playlistExtensions + sidecarExtensions + payloadExtensions +
            setOf("chd", "cdi", "pbp", "cso", "dax", "zso", "nrg", "rvz", "gcm", "gcz", "wbfs")

    /** `Game (Track 02)`, `Game - Track 3`, `track01`, ... */
    private val trackSuffix = Regex(
        "(?:^|[\\s._\\-(\\[])track\\s*_?\\d{1,3}\\s*[)\\]]?$",
        RegexOption.IGNORE_CASE,
    )

    /** `<playlist base> (Disc 2)`, `<playlist base> - CD 2`, ... */
    private val discSuffix = Regex(
        "^[\\s._\\-]*[(\\[]?\\s*(?:disc|disk|cd|side)\\s*_?\\d{1,2}\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    val all: List<GameSystem> = listOf(
        // Nintendo
        GameSystem("nes", "Nintendo Entertainment System", "NES", 3, setOf("nes", "fds", "unf", "unif"), setOf("nes", "famicom", "fc"), true, libretroFolder = "Nintendo - Nintendo Entertainment System"),
        GameSystem("snes", "Super Nintendo", "SNES", 4, setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "superfamicom", "supernintendo"), true, libretroFolder = "Nintendo - Super Nintendo Entertainment System"),
        GameSystem("gb", "Game Boy", "GB", 9, setOf("gb"), setOf("gb", "gameboy"), true, libretroFolder = "Nintendo - Game Boy"),
        GameSystem("gbc", "Game Boy Color", "GBC", 10, setOf("gbc"), setOf("gbc", "gameboycolor"), true, libretroFolder = "Nintendo - Game Boy Color"),
        GameSystem("gba", "Game Boy Advance", "GBA", 12, setOf("gba", "agb"), setOf("gba", "gameboyadvance"), true, libretroFolder = "Nintendo - Game Boy Advance"),
        GameSystem(
            "n64", "Nintendo 64", "N64", 14,
            setOf("z64", "n64", "v64", "ndd"),
            setOf("n64", "nintendo64", "mupen64plus", "mupen64", "n64dd"),
            true,
            libretroFolder = "Nintendo - Nintendo 64",
        ),
        GameSystem(
            "gamecube", "Nintendo GameCube", "GameCube", 13,
            // Dolphin's shared `rvz`/`iso` containers stay ambiguous on purpose; only
            // the two formats no other system writes are claimed by extension.
            setOf("gcm", "gcz"),
            setOf("gamecube", "gc", "ngc", "nintendogamecube", "dolphin-gc"),
            true,
            discBased = true,
            libretroFolder = "Nintendo - GameCube",
        ),
        GameSystem(
            "wii", "Nintendo Wii", "Wii", 16,
            setOf("wbfs"),
            setOf("wii", "nintendowii"),
            true,
            discBased = true,
            libretroFolder = "Nintendo - Wii",
        ),
        GameSystem("nds", "Nintendo DS", "NDS", 15, setOf("nds", "dsi"), setOf("nds", "ds", "nintendods"), true, libretroFolder = "Nintendo - Nintendo DS"),
        GameSystem("n3ds", "Nintendo 3DS", "3DS", 17, setOf("3ds", "cia"), setOf("3ds", "n3ds", "nintendo3ds"), true, libretroFolder = "Nintendo - Nintendo 3DS"),
        GameSystem("vb", "Virtual Boy", "VB", 11, setOf("vb"), setOf("vb", "virtualboy"), true, libretroFolder = "Nintendo - Virtual Boy"),
        // Sega
        GameSystem("megadrive", "Sega Genesis / Mega Drive", "Genesis", 1, setOf("md", "gen", "smd"), setOf("megadrive", "genesis", "md", "gen"), true, libretroFolder = "Sega - Mega Drive - Genesis"),
        GameSystem("mastersystem", "Sega Master System", "SMS", 2, setOf("sms"), setOf("mastersystem", "sms"), true, libretroFolder = "Sega - Master System - Mark III"),
        GameSystem("gamegear", "Sega Game Gear", "GG", 21, setOf("gg"), setOf("gamegear", "gg"), true, libretroFolder = "Sega - Game Gear"),
        GameSystem("segacd", "Sega CD / Mega CD", "Sega CD", 20, emptySet(), setOf("segacd", "megacd", "mega-cd", "sega-cd"), false, discBased = true, libretroFolder = "Sega - Mega-CD - Sega CD"),
        GameSystem("sega32x", "Sega 32X", "32X", 19, setOf("32x"), setOf("sega32x", "32x"), true, libretroFolder = "Sega - 32X"),
        GameSystem("saturn", "Sega Saturn", "Saturn", 22, emptySet(), setOf("saturn", "segasaturn"), true, discBased = true, libretroFolder = "Sega - Saturn"),
        GameSystem(
            "dreamcast", "Sega Dreamcast", "Dreamcast", 23,
            setOf("gdi", "cdi"),
            setOf("dreamcast", "dc", "segadreamcast", "flycast", "reicast", "redream"),
            true,
            discBased = true,
            libretroFolder = "Sega - Dreamcast",
        ),
        // Sony
        GameSystem(
            "psx", "Sony PlayStation", "PlayStation", 57,
            emptySet(),
            setOf("psx", "ps1", "playstation", "psone", "sonyplaystation", "pcsx", "pcsxrearmed", "duckstation", "swanstation"),
            true,
            discBased = true,
            libretroFolder = "Sony - PlayStation",
        ),
        GameSystem(
            "ps2", "Sony PlayStation 2", "PS2", 58,
            // `cso` is already claimed by PSP, and PS2's own containers are the
            // ambiguous ones, so this system is resolved by its folder.
            emptySet(),
            setOf("ps2", "playstation2", "sonyplaystation2", "pcsx2"),
            true,
            discBased = true,
            libretroFolder = "Sony - PlayStation 2",
        ),
        GameSystem(
            "psp", "Sony PSP", "PSP", 61,
            setOf("cso", "dax", "zso"),
            setOf("psp", "pspgo", "pspminis", "playstationportable", "ppsspp"),
            true,
            discBased = true,
            libretroFolder = "Sony - PlayStation Portable",
        ),
        // Arcade
        GameSystem("mame", "MAME", "MAME", 75, emptySet(), setOf("mame", "arcade"), false),
        GameSystem("fbneo", "FinalBurn Neo", "FBNeo", 75, emptySet(), setOf("fbneo", "fba", "fbn", "finalburn"), false),
        GameSystem("neogeo", "Neo Geo", "Neo Geo", 142, emptySet(), setOf("neogeo", "neo-geo"), false, libretroFolder = "SNK - Neo Geo"),
        GameSystem("neogeocd", "Neo Geo CD", "Neo Geo CD", 70, emptySet(), setOf("neogeocd", "neo-geo-cd"), false, discBased = true, libretroFolder = "SNK - Neo Geo CD"),
        GameSystem("ngp", "Neo Geo Pocket", "NGP", 25, setOf("ngp"), setOf("ngp", "neogeopocket"), true, libretroFolder = "SNK - Neo Geo Pocket"),
        GameSystem("ngpc", "Neo Geo Pocket Color", "NGPC", 82, setOf("ngc"), setOf("ngpc", "neogeopocketcolor"), true, libretroFolder = "SNK - Neo Geo Pocket Color"),
        // Atari
        GameSystem("atari2600", "Atari 2600", "2600", 26, setOf("a26"), setOf("atari2600", "2600", "a2600"), true, libretroFolder = "Atari - 2600"),
        GameSystem("atari5200", "Atari 5200", "5200", 40, setOf("a52"), setOf("atari5200", "5200", "a5200"), true, libretroFolder = "Atari - 5200"),
        GameSystem("atari7800", "Atari 7800", "7800", 41, setOf("a78"), setOf("atari7800", "7800", "a7800"), true, libretroFolder = "Atari - 7800"),
        GameSystem("lynx", "Atari Lynx", "Lynx", 28, setOf("lnx"), setOf("lynx", "atarilynx"), true, libretroFolder = "Atari - Lynx"),
        GameSystem("jaguar", "Atari Jaguar", "Jaguar", 27, setOf("j64", "jag"), setOf("jaguar", "atarijaguar"), true, libretroFolder = "Atari - Jaguar"),
        // Other
        GameSystem("pcengine", "PC Engine / TurboGrafx-16", "PCE", 31, setOf("pce"), setOf("pcengine", "tg16", "turbografx", "turbografx16"), true, libretroFolder = "NEC - PC Engine - TurboGrafx 16"),
        GameSystem("pcenginecd", "PC Engine CD / TurboGrafx-CD", "PCE CD", 114, emptySet(), setOf("pcenginecd", "tgcd", "turbografxcd", "pcecd"), false, discBased = true, libretroFolder = "NEC - PC Engine CD - TurboGrafx-CD"),
        GameSystem("wonderswan", "WonderSwan", "WS", 45, setOf("ws"), setOf("wonderswan", "ws"), true, libretroFolder = "Bandai - WonderSwan"),
        GameSystem("wonderswancolor", "WonderSwan Color", "WSC", 46, setOf("wsc"), setOf("wonderswancolor", "wsc"), true, libretroFolder = "Bandai - WonderSwan Color"),
        GameSystem("3do", "3DO", "3DO", 29, emptySet(), setOf("3do"), false, discBased = true, libretroFolder = "The 3DO Company - 3DO"),
        GameSystem("colecovision", "ColecoVision", "Coleco", 48, setOf("col"), setOf("colecovision", "coleco"), true, libretroFolder = "Coleco - ColecoVision"),
        GameSystem("intellivision", "Intellivision", "INTV", 115, setOf("int"), setOf("intellivision", "intv"), true, libretroFolder = "Mattel - Intellivision"),
        GameSystem("c64", "Commodore 64", "C64", 66, setOf("d64", "t64", "prg", "crt"), setOf("c64", "commodore64"), true, libretroFolder = "Commodore - 64"),
    )

    private val byExtension: Map<String, GameSystem> = buildMap {
        all.forEach { system ->
            system.extensions.forEach { extension ->
                if (extension !in ambiguousExtensions) put(extension, system)
            }
        }
    }

    /**
     * Folder name to system, in two tiers.
     *
     * Tier 1 is the hand-maintained alias list. Tier 2 is the libretro/RetroArch platform
     * folder names, which only fill gaps: `putIfAbsent` means a libretro name can never
     * displace an alias, and within each tier the system listed earlier in [all] wins. So
     * adding a libretro name can turn an unrecognised folder into a recognised one, but can
     * never change a folder that already resolved.
     */
    private val byFolderAlias: Map<String, GameSystem> = buildMap {
        all.forEach { system ->
            system.folderAliases.forEach { alias -> putIfAbsent(normalizeFolder(alias), system) }
        }
        all.forEach { system ->
            system.libretroFolder?.let { folder -> putIfAbsent(normalizeFolder(folder), system) }
        }
    }

    /** All extensions the scanner treats as possible ROMs. */
    val knownRomExtensions: Set<String> = byExtension.keys + ambiguousExtensions

    /** Systems this release can look artwork up for. */
    val scrapable: List<GameSystem> = all.filter { it.scrapingEnabled }

    fun byKey(key: String): GameSystem? = all.firstOrNull { it.key == key }

    private fun normalizeFolder(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun bySystemFolder(folderName: String?): GameSystem? {
        val normalized = normalizeFolder(folderName ?: return null)
        if (normalized.isEmpty()) return null
        return byFolderAlias[normalized]
    }

    /**
     * Resolves the system for a ROM. Folder context wins for ambiguous containers,
     * unambiguous extensions win otherwise.
     *
     * @param folderChain folder names from the ROM's own folder outwards to the library root
     */
    fun detect(fileName: String, folderChain: List<String>): GameSystem? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val fromFolder = folderChain.firstNotNullOfOrNull { bySystemFolder(it) }
        if (extension in ambiguousExtensions) return fromFolder
        return byExtension[extension] ?: fromFolder
    }

    /**
     * Picks the files in one folder that represent an actual game.
     *
     * Disc sets scatter several files per game — a `.cue` next to its `.bin` tracks, a
     * `.gdi` with dozens of tracks, a `.m3u` next to each of its discs. Only the file a
     * player would launch is kept, so the cover is named after it and each game is
     * looked up once instead of once per track.
     *
     * @param fileNames every file name in the folder (directories excluded)
     * @return the subset that should be treated as games
     */
    fun playableFileNames(fileNames: Collection<String>): Set<String> {
        val sheetBases = HashSet<String>()
        val playlistBases = HashSet<String>()
        fileNames.forEach { name ->
            val extension = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.', name).lowercase()
            if (extension in sheetExtensions) sheetBases += base
            if (extension in playlistExtensions) playlistBases += base
        }

        return fileNames.filterTo(LinkedHashSet()) { name ->
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in discExtensions) return@filterTo true
            val base = name.substringBeforeLast('.', name)
            val lowerBase = base.lowercase()
            when {
                extension in sidecarExtensions -> false
                trackSuffix.containsMatchIn(base) -> false
                extension in payloadExtensions && lowerBase in sheetBases -> false
                playlistBases.any { isDiscOfPlaylist(it, lowerBase) } -> false
                else -> true
            }
        }
    }

    private fun isDiscOfPlaylist(playlistBase: String, base: String): Boolean {
        if (base == playlistBase || !base.startsWith(playlistBase)) return false
        return discSuffix.matches(base.substring(playlistBase.length))
    }
}
