package com.rork.rgdsartworkprep.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Streaming checksum helpers — large ROMs are never fully loaded into memory. */
object Checksums {

    private const val TAG = "Checksums"
    private const val BUFFER_SIZE = 64 * 1024

    /** Files above this size skip hashing and fall back to filename search. */
    const val MAX_HASHED_BYTES: Long = 128L * 1024 * 1024

    /** @return uppercase CRC32 hex, or null when the ROM is too large or unreadable. */
    suspend fun crc32(resolver: ContentResolver, uri: Uri, sizeBytes: Long): String? {
        if (sizeBytes > MAX_HASHED_BYTES) return null
        return withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.use { stream ->
                    val digest = CRC32()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.value.toString(16).uppercase().padStart(8, '0')
                }
            } catch (error: Exception) {
                Log.w(TAG, "CRC32 failed for a ROM: ${error.javaClass.simpleName}")
                null
            }
        }
    }
}
