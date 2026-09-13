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
)

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
)
