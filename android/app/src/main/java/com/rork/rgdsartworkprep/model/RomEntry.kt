package com.rork.rgdsartworkprep.model

import android.net.Uri
import com.rork.rgdsartworkprep.data.RomLocations

/** A ROM file discovered inside the user-selected library tree (or hand-picked via SAF). */
data class RomEntry(
    val documentId: String,
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val parentDocumentId: String?,
    /** Folder names from the ROM's own folder outwards, e.g. `["GBA", "Roms"]`. */
    val folderChain: List<String>,
    val system: GameSystem?,
    val artworkUri: Uri? = null,
    /** Artwork location relative to the ROM's own folder, e.g. `Imgs/zelda.jpg`. */
    val artworkRelativePath: String? = null,
    /** Document id of the system folder this ROM belongs to, when it sits below one. */
    val systemRootDocumentId: String? = null,
    /** Name of that system folder, e.g. `PSX`. */
    val systemRootFolderName: String? = null,
    /**
     * Folder path from the system folder down to the ROM's own folder, e.g.
     * `Final Fantasy VII` for `PSX/Final Fantasy VII/ff7.cue`. Null when the ROM sits
     * directly in the system folder.
     */
    val subPath: String? = null,
    /**
     * The saved ROM location this file was found in — the tree whose permission is used
     * to read it and to write its artwork and gamelist.xml. Null for a file picked by
     * hand from outside every saved location.
     */
    val locationTreeUri: String? = null,
    /**
     * Keeps this ROM's identity apart from a same-named file in another location. Null
     * for the folder carried over from a single-folder build (and for the first folder
     * of a fresh install), whose ROMs keep exactly the identity they always had.
     */
    val locationScope: String? = null,
) {
    /**
     * Stable identity of this file within the app. Two locations can hold the same
     * filename — and on a provider that does not prefix document ids with the volume,
     * even the same document id — so a scoped location's ROMs carry their scope.
     */
    val id: String = RomLocations.romId(documentId, locationScope)

    /** Filename without its extension — artwork must be saved under exactly this name. */
    val baseName: String get() = fileName.substringBeforeLast('.', fileName)

    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    val systemFolderName: String? get() = systemRootFolderName ?: folderChain.firstOrNull()

    val hasArtwork: Boolean get() = artworkUri != null

    /** Path used inside gamelist.xml, relative to the system folder. */
    val gamelistRomPath: String get() = subPath?.let { "$it/$fileName" } ?: fileName

    /** Turns a path relative to the ROM's folder into one relative to the system folder. */
    fun gamelistRelativePath(pathInRomFolder: String): String {
        val clean = pathInRomFolder.removePrefix("./")
        return subPath?.let { "./$it/$clean" } ?: "./$clean"
    }
}
