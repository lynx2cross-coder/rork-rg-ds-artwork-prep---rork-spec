package com.rork.rgdsartworkprep.domain.queue

import android.net.Uri
import com.rork.rgdsartworkprep.model.RomEntry
import com.rork.rgdsartworkprep.model.SystemCatalog

/**
 * Converts a discovered ROM into the flat form that can be written to disk.
 *
 * The detected system is stored as its key rather than the object, so a snapshot
 * written by one build still resolves correctly after the catalog gains or renames
 * entries.
 */
fun RomEntry.toRecord(): RomRecord = RomRecord(
    documentId = documentId,
    uri = uri.toString(),
    fileName = fileName,
    sizeBytes = sizeBytes,
    parentDocumentId = parentDocumentId,
    folderChain = folderChain,
    systemKey = system?.key,
    artworkUri = artworkUri?.toString(),
    artworkRelativePath = artworkRelativePath,
    systemRootDocumentId = systemRootDocumentId,
    systemRootFolderName = systemRootFolderName,
    subPath = subPath,
    locationTreeUri = locationTreeUri,
    locationScope = locationScope,
)

/**
 * Fills in the location for ROMs saved by a build that had only one library folder.
 *
 * A 1.5.x snapshot recorded that folder once, as [ScanSnapshot.sourceTreeUri], and
 * nothing on each ROM. A scan interrupted before updating and resumed after it would
 * otherwise have no folder to write into and send every cover to the export folder.
 * Only ROMs that were inside the library (they have a parent folder) are filled in;
 * the scope stays null, so their ids and remembered matches are exactly as before.
 */
fun ScanSnapshot.withLegacyLocations(): ScanSnapshot {
    val legacyTree = sourceTreeUri ?: return this
    fun needsFill(record: RomRecord) = record.locationTreeUri == null && record.parentDocumentId != null
    if (roms.none(::needsFill)) return this
    return copy(roms = roms.map { if (needsFill(it)) it.copy(locationTreeUri = legacyTree) else it })
}

/**
 * Rebuilds a ROM from its persisted record.
 *
 * The system is looked up by key and, failing that, re-detected from the filename and
 * folders — which means a resumed scan picks up detection improvements shipped since
 * the snapshot was written instead of being stuck with the old verdict.
 */
fun RomRecord.toEntry(): RomEntry = RomEntry(
    documentId = documentId,
    uri = Uri.parse(uri),
    fileName = fileName,
    sizeBytes = sizeBytes,
    parentDocumentId = parentDocumentId,
    folderChain = folderChain,
    system = systemKey?.let(SystemCatalog::byKey) ?: SystemCatalog.detect(fileName, folderChain),
    artworkUri = artworkUri?.let(Uri::parse),
    artworkRelativePath = artworkRelativePath,
    systemRootDocumentId = systemRootDocumentId,
    systemRootFolderName = systemRootFolderName,
    subPath = subPath,
    locationTreeUri = locationTreeUri,
    locationScope = locationScope,
)
