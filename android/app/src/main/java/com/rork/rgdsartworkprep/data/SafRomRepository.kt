package com.rork.rgdsartworkprep.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.RomEntry
import com.rork.rgdsartworkprep.model.SystemCatalog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Result of walking the selected ROM library. */
data class LibraryScan(
    val roms: List<RomEntry> = emptyList(),
    val scannedAtMillis: Long = 0L,
) {
    val total: Int get() = roms.size
    val withArtwork: Int get() = roms.count { it.hasArtwork }
    val missingArtwork: Int get() = roms.count { !it.hasArtwork }
    val missing: List<RomEntry> get() = roms.filter { !it.hasArtwork }
    val prepared: List<RomEntry> get() = roms.filter { it.hasArtwork }
}

/** Where a written file ended up. */
sealed interface FileWriteResult {
    data class SavedToLibrary(val displayPath: String, val fileName: String) : FileWriteResult
    data class ExportedToAppStorage(
        val displayPath: String,
        val reason: String,
        val fileName: String,
    ) : FileWriteResult

    data class Failed(val reason: String) : FileWriteResult
}

/** An artwork file that is already sitting next to a ROM, e.g. `Imgs/zelda.jpg`. */
data class ExistingArtwork(val uri: Uri, val relativePath: String)

/**
 * All Storage Access Framework work: scanning the library, creating `Imgs`, writing covers,
 * merging gamelist.xml and the ready-to-copy export fallback.
 *
 * ROM files are only ever read — never renamed, moved, modified or deleted.
 */
class SafRomRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    // region permissions

    fun persistTreePermission(treeUri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (error: SecurityException) {
        Log.w(TAG, "Could not persist tree permission: ${error.javaClass.simpleName}")
        false
    }

    fun hasWriteAccess(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /** Human-readable label such as `Download/Roms`. */
    fun describeTree(treeUri: Uri): String = try {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        documentId.substringAfter(':', documentId).ifBlank { documentId }
    } catch (error: Exception) {
        treeUri.lastPathSegment ?: "Selected folder"
    }

    // endregion

    // region scanning

    suspend fun scanLibrary(treeUri: Uri): LibraryScan = withContext(Dispatchers.IO) {
        val roms = mutableListOf<RomEntry>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootName = describeTree(treeUri).substringAfterLast('/')
            val rootSystem = SystemCatalog.bySystemFolder(rootName)
            walk(
                treeUri = treeUri,
                parentDocumentId = rootId,
                folderChain = if (rootSystem != null) listOf(rootName) else emptyList(),
                systemRootDocumentId = if (rootSystem != null) rootId else null,
                systemRootFolderName = if (rootSystem != null) rootName else null,
                subPath = null,
                output = roms,
                depth = 0,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Library scan stopped early: ${error.javaClass.simpleName}")
        }
        LibraryScan(roms = roms.sortedBy { it.fileName.lowercase() }, scannedAtMillis = System.currentTimeMillis())
    }

    private suspend fun walk(
        treeUri: Uri,
        parentDocumentId: String,
        folderChain: List<String>,
        systemRootDocumentId: String?,
        systemRootFolderName: String?,
        subPath: String?,
        output: MutableList<RomEntry>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        coroutineContext.ensureActive()

        val children = listChildren(treeUri, parentDocumentId)
        if (children.isEmpty()) return

        val artworkDirs = children.filter { it.isDirectory && it.name.lowercase() in ARTWORK_DIRS }
        val artworkByBase = mutableMapOf<String, ExistingArtwork>()

        artworkDirs.forEach { dir ->
            listChildren(treeUri, dir.documentId)
                .filter { !it.isDirectory && it.extension in SystemCatalog.imageExtensions }
                .forEach { image ->
                    artworkByBase.putIfAbsent(
                        image.baseName.lowercase(),
                        ExistingArtwork(image.uri(treeUri), "${dir.name}/${image.name}"),
                    )
                }
        }
        // Artwork sitting directly beside the ROM also counts as present.
        children.filter { !it.isDirectory && it.extension in SystemCatalog.imageExtensions }
            .forEach { image ->
                artworkByBase.putIfAbsent(
                    image.baseName.lowercase(),
                    ExistingArtwork(image.uri(treeUri), image.name),
                )
            }

        // Disc sets scatter sheets, tracks and playlists across many files — only the
        // file a player would actually launch becomes a game.
        val candidateFiles = children.filter { child ->
            !child.isDirectory &&
                !child.name.startsWith(".") &&
                child.extension.isNotBlank() &&
                child.extension !in SystemCatalog.imageExtensions &&
                child.extension in SystemCatalog.knownRomExtensions
        }
        val playable = SystemCatalog.playableFileNames(candidateFiles.map { it.name })

        candidateFiles.forEach { child ->
            val extension = child.extension
            if (child.name !in playable) return@forEach

            val system: GameSystem? = SystemCatalog.detect(child.name, folderChain)
            if (system == null && extension in SystemCatalog.ambiguousExtensions) return@forEach

            val artwork = artworkByBase[child.baseName.lowercase()]
            output += RomEntry(
                documentId = child.documentId,
                uri = child.uri(treeUri),
                fileName = child.name,
                sizeBytes = child.size,
                parentDocumentId = parentDocumentId,
                folderChain = folderChain,
                system = system,
                artworkUri = artwork?.uri,
                artworkRelativePath = artwork?.relativePath,
                systemRootDocumentId = systemRootDocumentId,
                systemRootFolderName = systemRootFolderName,
                subPath = subPath,
            )
        }

        children.filter { it.isDirectory }.forEach { dir ->
            val lowerName = dir.name.lowercase()
            if (lowerName in ARTWORK_DIRS || lowerName in SKIPPED_DIRS || dir.name.startsWith(".")) return@forEach
            val entersSystemRoot = systemRootDocumentId == null && SystemCatalog.bySystemFolder(dir.name) != null
            walk(
                treeUri = treeUri,
                parentDocumentId = dir.documentId,
                folderChain = listOf(dir.name) + folderChain,
                systemRootDocumentId = if (entersSystemRoot) dir.documentId else systemRootDocumentId,
                systemRootFolderName = if (entersSystemRoot) dir.name else systemRootFolderName,
                subPath = when {
                    systemRootDocumentId == null -> null
                    subPath == null -> dir.name
                    else -> "$subPath/${dir.name}"
                },
                output = output,
                depth = depth + 1,
            )
        }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
        val baseName: String get() = name.substringBeforeLast('.', name)
        fun uri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val result = mutableListOf<Child>()
        try {
            resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    result += Child(documentId, name, mime, size)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not list a folder: ${error.javaClass.simpleName}")
        }
        return result
    }

    // endregion

    // region hand-picked ROM files

    /**
     * Converts a document picked with `ACTION_OPEN_DOCUMENT` into a [RomEntry].
     * When the file lives inside the granted library tree, the tree-backed URI is used so
     * artwork can be written next to it.
     */
    fun romFromPickedDocument(pickedUri: Uri, treeUri: Uri?): RomEntry? {
        val documentId = try {
            DocumentsContract.getDocumentId(pickedUri)
        } catch (error: Exception) {
            null
        }
        val displayName = queryDisplayName(pickedUri) ?: documentId?.substringAfterLast('/') ?: return null
        val size = querySize(pickedUri)

        val treeRootId = treeUri?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        }
        val insideTree = documentId != null && treeRootId != null &&
            (documentId == treeRootId || documentId.startsWith("$treeRootId/"))

        val pathSegments = documentId?.substringAfter(':', "")?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        val folderChain = if (pathSegments.size >= 2) {
            pathSegments.dropLast(1).reversed()
        } else {
            emptyList()
        }

        val effectiveUri = if (insideTree && treeUri != null && documentId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            pickedUri
        }
        val parentDocumentId = if (insideTree && documentId != null && documentId.contains('/')) {
            documentId.substringBeforeLast('/')
        } else {
            null
        }

        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank() || extension in SystemCatalog.imageExtensions) return null

        return RomEntry(
            documentId = documentId ?: pickedUri.toString(),
            uri = effectiveUri,
            fileName = displayName,
            sizeBytes = size,
            parentDocumentId = parentDocumentId,
            folderChain = folderChain,
            system = SystemCatalog.detect(displayName, folderChain),
            artworkUri = null,
        )
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (error: Exception) {
        null
    }

    private fun querySize(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
    } catch (error: Exception) {
        0L
    }

    // endregion

    // region artwork output

    /** The same-named cover that already exists for this ROM, if any. */
    suspend fun findExistingArtwork(treeUri: Uri, rom: RomEntry): ExistingArtwork? = withContext(Dispatchers.IO) {
        val parentId = rom.parentDocumentId ?: return@withContext null
        val imgsDir = listChildren(treeUri, parentId)
            .firstOrNull { it.isDirectory && it.name.lowercase() in ARTWORK_DIRS }
            ?: return@withContext null
        listChildren(treeUri, imgsDir.documentId)
            .firstOrNull {
                !it.isDirectory &&
                    it.extension in SystemCatalog.imageExtensions &&
                    it.baseName.equals(rom.baseName, ignoreCase = true)
            }
            ?.let { ExistingArtwork(it.uri(treeUri), "${imgsDir.name}/${it.name}") }
    }

    /**
     * Writes the cover to `<romFolder>/Imgs/<romBaseName>.jpg`, falling back to the
     * ready-to-copy export package when the library cannot be written to.
     */
    suspend fun saveArtwork(
        treeUri: Uri?,
        rom: RomEntry,
        bytes: ByteArray,
        overwrite: Boolean,
        forceExport: Boolean,
    ): FileWriteResult = withContext(Dispatchers.IO) {
        if (!forceExport && treeUri != null && rom.parentDocumentId != null) {
            val direct = runCatching { writeIntoLibrary(treeUri, rom, bytes, overwrite) }
                .getOrElse { error ->
                    Log.w(TAG, "Direct write failed: ${error.javaClass.simpleName}")
                    null
                }
            if (direct != null) return@withContext direct
        }
        // Game sub-folders are mirrored so the export can be copied back in one drag.
        val exportSubFolder = rom.subPath?.let { "$it/$IMGS_DIR_NAME" } ?: IMGS_DIR_NAME
        val prepared = ArtworkImage.prepare(rom.baseName, bytes)
        exportForManualCopy(
            systemFolder = rom.systemFolderName ?: rom.system?.shortName ?: "Unsorted",
            subFolder = exportSubFolder,
            fileName = prepared.fileName,
            bytes = prepared.bytes,
            reason = exportReason(forceExport, treeUri, rom.parentDocumentId),
            relativeName = "$IMGS_DIR_NAME/${prepared.fileName}",
        )
    }

    private fun exportReason(forceExport: Boolean, treeUri: Uri?, parentDocumentId: String?): String = when {
        forceExport -> "Export fallback is on in Settings"
        treeUri == null -> "No library folder with write access"
        parentDocumentId == null -> "ROM is outside the selected library folder"
        else -> "Library folder is not writable"
    }

    private fun writeIntoLibrary(
        treeUri: Uri,
        rom: RomEntry,
        bytes: ByteArray,
        overwrite: Boolean,
    ): FileWriteResult? {
        val parentId = rom.parentDocumentId ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)

        val existingDir = listChildren(treeUri, parentId)
            .firstOrNull { it.isDirectory && it.name.lowercase() in ARTWORK_DIRS }
        val imgsUri = if (existingDir != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDir.documentId)
        } else {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                IMGS_DIR_NAME,
            )
        } ?: return null

        val imgsId = DocumentsContract.getDocumentId(imgsUri)
        val imgsName = existingDir?.name ?: IMGS_DIR_NAME

        // The extension comes from the bytes themselves, so the name never claims a
        // format the file does not hold.
        val prepared = ArtworkImage.prepare(rom.baseName, bytes)
        val siblings = listChildren(treeUri, imgsId).filter {
            !it.isDirectory &&
                it.extension in SystemCatalog.imageExtensions &&
                it.baseName.equals(rom.baseName, ignoreCase = true)
        }
        if (siblings.isNotEmpty() && !overwrite) return FileWriteResult.Failed("Artwork already exists")

        val sameName = siblings.firstOrNull { it.name.equals(prepared.fileName, ignoreCase = true) }
        val targetUri = sameName?.uri(treeUri)
            ?: DocumentsContract.createDocument(resolver, imgsUri, prepared.mimeType, prepared.fileName)
            ?: return null

        resolver.openOutputStream(targetUri, "wt")?.use { it.write(prepared.bytes) } ?: return null

        val savedName = queryDisplayName(targetUri) ?: prepared.fileName
        // A rescrape that changes format would otherwise leave the old cover behind and
        // the frontend would have two images for one ROM. Only artwork we wrote is
        // removed here — ROM files are never touched.
        siblings.filterNot { it.name.equals(savedName, ignoreCase = true) }
            .forEach { stale ->
                runCatching { DocumentsContract.deleteDocument(resolver, stale.uri(treeUri)) }
                    .onFailure { Log.w(TAG, "Could not remove superseded cover ${stale.extension}") }
            }

        val folder = listOfNotNull(rom.systemFolderName, rom.subPath).joinToString("/")
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return FileWriteResult.SavedToLibrary("$prefix$imgsName/$savedName", "$imgsName/$savedName")
    }

    // endregion

    // region gamelist.xml

    /** Reads an existing gamelist.xml from a system folder so it can be merged, not clobbered. */
    suspend fun readGamelist(treeUri: Uri, parentDocumentId: String): String? = withContext(Dispatchers.IO) {
        val file = listChildren(treeUri, parentDocumentId)
            .firstOrNull { !it.isDirectory && it.name.equals(GAMELIST_FILE_NAME, ignoreCase = true) }
            ?: return@withContext null
        if (file.size > MAX_GAMELIST_BYTES) {
            Log.w(TAG, "Existing gamelist is unusually large, leaving it untouched")
            return@withContext null
        }
        try {
            resolver.openInputStream(file.uri(treeUri))?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read existing gamelist: ${error.javaClass.simpleName}")
            null
        }
    }

    /** Writes gamelist.xml into the ROM folder, or into the export package as a fallback. */
    suspend fun saveGamelist(
        treeUri: Uri?,
        parentDocumentId: String?,
        systemFolderName: String?,
        xml: String,
        forceExport: Boolean,
    ): FileWriteResult = withContext(Dispatchers.IO) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        if (!forceExport && treeUri != null && parentDocumentId != null) {
            val direct = runCatching { writeGamelistIntoLibrary(treeUri, parentDocumentId, systemFolderName, bytes) }
                .getOrElse { error ->
                    Log.w(TAG, "Direct gamelist write failed: ${error.javaClass.simpleName}")
                    null
                }
            if (direct != null) return@withContext direct
        }
        exportForManualCopy(
            systemFolder = systemFolderName ?: "Unsorted",
            subFolder = null,
            fileName = GAMELIST_FILE_NAME,
            bytes = bytes,
            reason = exportReason(forceExport, treeUri, parentDocumentId),
        )
    }

    private fun writeGamelistIntoLibrary(
        treeUri: Uri,
        parentDocumentId: String,
        systemFolderName: String?,
        bytes: ByteArray,
    ): FileWriteResult? {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val existing = listChildren(treeUri, parentDocumentId)
            .firstOrNull { !it.isDirectory && it.name.equals(GAMELIST_FILE_NAME, ignoreCase = true) }

        val targetUri = existing?.uri(treeUri)
            ?: DocumentsContract.createDocument(resolver, parentUri, "text/xml", GAMELIST_FILE_NAME)
            ?: return null

        resolver.openOutputStream(targetUri, "wt")?.use { it.write(bytes) } ?: return null

        val savedName = existing?.name ?: queryDisplayName(targetUri) ?: GAMELIST_FILE_NAME
        val folder = systemFolderName?.let { "$it/" }.orEmpty()
        return FileWriteResult.SavedToLibrary("$folder$savedName", savedName)
    }

    // endregion

    // region export fallback

    /** Builds `RGDS Artwork Export/<System>/[Imgs/]<file>` inside the app's own storage. */
    private fun exportForManualCopy(
        systemFolder: String,
        subFolder: String?,
        fileName: String,
        bytes: ByteArray,
        reason: String,
        relativeName: String = if (subFolder != null) "$subFolder/$fileName" else fileName,
    ): FileWriteResult = try {
        val relativeFolder = if (subFolder != null) "$systemFolder/$subFolder" else systemFolder
        val target = File(exportRoot(), relativeFolder)
        target.mkdirs()
        val file = File(target, fileName)
        file.writeBytes(bytes)
        FileWriteResult.ExportedToAppStorage(
            displayPath = "$relativeFolder/${file.name}",
            reason = reason,
            fileName = relativeName,
        )
    } catch (error: Exception) {
        Log.w(TAG, "Export fallback failed: ${error.javaClass.simpleName}")
        FileWriteResult.Failed("Could not save $fileName")
    }

    fun exportRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, EXPORT_DIR_NAME)

    fun exportRootDisplayPath(): String = "Android/data/${context.packageName}/files/$EXPORT_DIR_NAME"

    fun exportedFileCount(): Int = try {
        exportRoot().walkTopDown().count { it.isFile }
    } catch (error: Exception) {
        0
    }

    // endregion

    private companion object {
        const val TAG = "SafRomRepository"
        const val MAX_DEPTH = 6
        const val IMGS_DIR_NAME = "Imgs"
        const val EXPORT_DIR_NAME = "RGDS Artwork Export"
        const val GAMELIST_FILE_NAME = "gamelist.xml"
        const val MAX_GAMELIST_BYTES = 16L * 1024 * 1024
        val ARTWORK_DIRS = setOf("imgs", "images", "media", "boxart", "covers")
        val SKIPPED_DIRS = setOf("bios", "saves", "savestates", "cheats", "system", "android", "gamelists")
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
