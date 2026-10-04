package com.rork.rgdsartworkprep.data

import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable

/**
 * One saved ROM root folder.
 *
 * Only the Storage Access Framework tree URI is stored — never a file path. A path
 * says nothing about access: after a restart or reboot the app can reach the folder
 * only through the URI whose permission it persisted when the user picked it, and
 * internal storage and an SD card hold separate grants.
 *
 * @param treeUri the exact tree URI the folder picker returned
 * @param addedAtMillis when the user added it; keeps the list in the order it was built
 * @param legacyIdentity true for the single library folder carried over from 1.5.x,
 *   and for the first folder added to an empty list. ROMs found there keep exactly the
 *   identity a single-folder build gave them — the same ids, remembered matches and
 *   export layout — so updating costs nothing. Every other location's ROMs are scoped
 *   to their location, so a same-named file elsewhere can never share them. At most
 *   one saved location has this at a time.
 */
@Serializable
data class RomLocation(
    val treeUri: String,
    val addedAtMillis: Long = 0L,
    val legacyIdentity: Boolean = false,
) {
    /** Identity used to refuse the same folder twice, however its URI was encoded. */
    val key: String get() = RomLocations.keyOf(treeUri)

    val label: RomLocationLabel.Parts get() = RomLocationLabel.of(treeUri)
}

/** The provider and folder a tree URI points at, decoded. Pure, so it runs on the JVM. */
data class TreeRef(val authority: String, val treeDocumentId: String) {

    companion object {
        /**
         * Reads `content://<authority>/tree/<encoded document id>[/document/...]`.
         *
         * @return null for anything that is not a SAF tree URI
         */
        fun parse(uri: String): TreeRef? {
            val trimmed = uri.trim()
            if (!trimmed.startsWith(SCHEME)) return null
            val rest = trimmed.removePrefix(SCHEME).substringBefore('?').substringBefore('#')
            val authority = rest.substringBefore('/')
            val segments = rest.substringAfter('/', "").split('/')
            val treeIndex = segments.indexOf("tree")
            if (authority.isEmpty() || treeIndex < 0 || treeIndex + 1 >= segments.size) return null
            val documentId = percentDecode(segments[treeIndex + 1])
            // `primary:Roms/` and `primary:Roms` are one folder; a volume root keeps its colon.
            val normalized = documentId.trimEnd('/')
            if (normalized.isEmpty()) return null
            return TreeRef(authority.lowercase(), normalized)
        }

        private const val SCHEME = "content://"
    }
}

/** Rules for comparing and locating saved ROM folders. */
object RomLocations {

    /**
     * Two URIs naming the same folder produce the same key: the authority is
     * case-folded and the document id decoded, so `primary%3ARoms` and `primary:Roms`
     * cannot both be saved. Folder names themselves are compared exactly.
     */
    fun keyOf(treeUri: String): String =
        TreeRef.parse(treeUri)?.let { "${it.authority}|${it.treeDocumentId}" } ?: treeUri.trim()

    /**
     * The saved location a document lives in, for files picked by hand.
     *
     * Locations may nest (an SD card's `/Roms` and its `/Roms/3DS` can both be saved);
     * the first one in the user's list wins, which is the same rule the library scan
     * uses to keep one copy of a file two locations both reach.
     */
    fun containing(locations: List<RomLocation>, authority: String?, documentId: String?): RomLocation? {
        if (authority == null || documentId == null) return null
        val wantedAuthority = authority.lowercase()
        return locations.firstOrNull { location ->
            val tree = TreeRef.parse(location.treeUri) ?: return@firstOrNull false
            tree.authority == wantedAuthority && isInside(documentId, tree.treeDocumentId)
        }
    }

    /**
     * The saved-location tree a ROM's artwork and gamelist.xml are written into: always
     * the ROM's own location, never another saved one. Null — no location, or one that
     * is not writable — sends the file to the ready-to-copy export folder, as before.
     */
    fun outputTree(locationTreeUri: String?, hasWriteAccess: (String) -> Boolean): String? =
        locationTreeUri?.takeIf(hasWriteAccess)

    /** The scope a location's ROMs carry; null keeps the single-folder identity. */
    fun scopeOf(location: RomLocation?): String? =
        location?.takeUnless { it.legacyIdentity }?.key

    /** A ROM's app-wide id. Unscoped ids are the bare document id, as before. */
    fun romId(documentId: String, scope: String?): String =
        if (scope == null) documentId else "$scope#$documentId"

    /**
     * Folder a scoped ROM's covers and gamelist.xml go under in the ready-to-copy
     * export, so two locations' `GBA/Imgs/Mario.png` cannot overwrite each other.
     * Null keeps the single-folder export layout.
     */
    fun exportPrefix(locationTreeUri: String?, scope: String?): String? =
        if (scope == null || locationTreeUri == null) null else RomLocationLabel.exportFolderName(locationTreeUri)

    /**
     * Groups gamelist.xml entries by location *and* system folder. Two locations each
     * with a `GBA` folder get two gamelists, each written into its own folder.
     */
    fun gamelistBucketKey(locationTreeUri: String?, folderId: String?, systemFolderName: String?): String {
        val location = locationTreeUri?.let(::keyOf).orEmpty()
        val folder = folderId ?: systemFolderName ?: "unsorted"
        return "$location|$folder"
    }

    /** Collapses one file reached through two nested locations into one entry. */
    fun fileKey(location: RomLocation, documentId: String): String =
        "${TreeRef.parse(location.treeUri)?.authority ?: location.key}|$documentId"

    private fun isInside(documentId: String, treeDocumentId: String): Boolean {
        if (documentId == treeDocumentId) return true
        // A volume root (`primary:`) already ends in its separator.
        val prefix = if (treeDocumentId.endsWith(':')) treeDocumentId else "$treeDocumentId/"
        return documentId.startsWith(prefix)
    }
}

/**
 * Whether a saved location can be reached right now.
 *
 * A location is never deleted for being unreachable: an SD card that is out of the
 * device today is back tomorrow, and the user decides whether to remove it.
 */
enum class LocationAccess {
    /** Readable and writable: artwork is saved straight into the folder. */
    Available,

    /** Readable only: it is scanned, and its artwork goes to the export folder. */
    ReadOnly,

    /** Android no longer holds a permission for it — revoked, or cleared with app data. */
    PermissionLost,

    /** The permission is held but the folder cannot be opened: moved, deleted or card removed. */
    NotFound;

    val isScannable: Boolean get() = this == Available || this == ReadOnly

    /** One line for the ROM Locations row. */
    val description: String
        get() = when (this) {
            Available -> "Available"
            ReadOnly -> "Available \u00b7 read-only, artwork goes to the export folder"
            PermissionLost -> "Folder access is no longer available"
            NotFound -> "Folder not found \u2014 moved, deleted or SD card removed"
        }

    companion object {
        /**
         * Pure decision from what Android reports, so every branch is testable.
         *
         * The grant is checked first: without one, a readable folder is a leftover of
         * this process's temporary access and will be gone after a restart.
         */
        fun decide(hasReadGrant: Boolean, hasWriteGrant: Boolean, rootReadable: Boolean): LocationAccess = when {
            !hasReadGrant -> PermissionLost
            !rootReadable -> NotFound
            hasWriteGrant -> Available
            else -> ReadOnly
        }
    }
}

/** Plain-language names for a saved folder: "SD card" and "/Roms", never a raw URI. */
object RomLocationLabel {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    data class Parts(val volume: String, val path: String) {
        /** e.g. `SD card /Roms/3DS`. */
        val full: String get() = "$volume $path"
    }

    fun of(treeUri: String): Parts {
        val tree = TreeRef.parse(treeUri) ?: return Parts(OTHER_VOLUME, "/")
        val volumeId = tree.treeDocumentId.substringBefore(':', "")
        val relative = tree.treeDocumentId.substringAfter(':', tree.treeDocumentId).trim('/')
        val external = tree.authority == EXTERNAL_STORAGE_AUTHORITY
        val volume = when {
            !external || volumeId.isEmpty() -> OTHER_VOLUME
            volumeId == "primary" || volumeId == "home" -> "Internal storage"
            else -> "SD card"
        }
        // `home:` is the Documents folder of internal storage.
        val base = if (external && volumeId == "home") "Documents" else ""
        val path = listOf(base, relative).filter { it.isNotEmpty() }.joinToString("/")
        return Parts(volume, "/$path")
    }

    /** A saved-artwork path as shown to the user, e.g. `SD card /Roms/GBA/Imgs/Mario.png`. */
    fun displayPath(treeUri: String, relative: String): String {
        val parts = of(treeUri)
        return "${parts.volume} ${parts.path.trimEnd('/')}/${relative.trimStart('/')}"
    }

    /**
     * A folder name for this location inside the ready-to-copy export, used only when
     * several locations are saved — so two `GBA/Imgs/Mario.png` covers from different
     * locations can never overwrite each other there.
     */
    fun exportFolderName(treeUri: String): String {
        val parts = of(treeUri)
        // An SD card is named by its volume id here, so two cards (or a card swapped
        // for another) can never share an export folder.
        val volumeId = TreeRef.parse(treeUri)?.treeDocumentId?.substringBefore(':', "").orEmpty()
        val volume = if (parts.volume == "SD card" && volumeId.isNotEmpty()) "SD card $volumeId" else parts.volume
        val path = parts.path.trim('/').replace('/', ' ')
        val raw = if (path.isEmpty()) volume else "$volume $path"
        return raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private const val OTHER_VOLUME = "Storage"
}

/** Decodes `%XX` escapes as UTF-8, leaving `+` alone — unlike URLDecoder, which is for forms. */
internal fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (byte != null) {
                out.write(byte)
                index += 3
                continue
            }
        }
        val codePoint = value.codePointAt(index)
        out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        index += Character.charCount(codePoint)
    }
    return out.toString(Charsets.UTF_8.name())
}
