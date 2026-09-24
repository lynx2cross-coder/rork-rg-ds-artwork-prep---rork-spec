package com.rork.rgdsartworkprep.data

/**
 * Filenames offered as a one-tap addition in Settings -> Ignored files.
 *
 * A convenience for the user, not a rule of the app. Nothing reads this list except
 * the button that offers it: the scanner does not consult it, no install receives it
 * automatically, and once added its names are ordinary entries the user can remove
 * one by one. The scanner only ever sees the user's own list.
 */
object IgnoredFilePresets {

    /**
     * Support files a Nintendo 3DS emulator setup keeps beside its games. Their `.bin`
     * extension, sitting in a 3DS folder, is what makes a scan mistake them for games.
     */
    val threeDsSystemFiles: List<String> = listOf(
        "boot9.bin",
        "boot11.bin",
        "seeddb.bin",
        "shared_font.bin",
    )

    /** The preset names not yet on [ignored], in preset order. */
    fun missingFrom(ignored: IgnoredFiles, preset: List<String> = threeDsSystemFiles): List<String> =
        preset.filterNot { ignored.matches(it) }
}
