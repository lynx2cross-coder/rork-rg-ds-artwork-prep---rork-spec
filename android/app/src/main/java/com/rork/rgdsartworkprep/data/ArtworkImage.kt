package com.rork.rgdsartworkprep.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Makes a downloaded cover's filename describe what the file actually contains.
 *
 * Artwork sources cannot be trusted to describe what they send: Hasheous serves
 * `Content-Type: image/jpeg` for images that are really PNG. Rather than re-compressing
 * a perfectly good picture, the container is detected from its own bytes and the file
 * is named to match — PNG bytes are saved as `.png`, JPEG bytes as `.jpg`.
 *
 * Formats a handheld frontend is unlikely to read (WebP, GIF, BMP) are the one case
 * that is converted, to JPEG, because renaming those would not make them readable.
 */
object ArtworkImage {

    /** Image container detected from the leading bytes, ignoring any declared type. */
    enum class Format {
        Jpeg,
        Png,
        Webp,
        Gif,
        Bmp,
        Unknown,
        ;

        val displayName: String get() = name.uppercase()
    }

    /** A cover ready to be written: the bytes, and the name that honestly describes them. */
    class Prepared(val fileName: String, val bytes: ByteArray, val mimeType: String)

    /**
     * Identifies the container from its signature.
     *
     * Only the magic bytes are read; headers and file extensions are deliberately
     * ignored because they are the thing being verified.
     */
    fun detect(bytes: ByteArray): Format = when {
        bytes.size < MIN_SIGNATURE_BYTES -> Format.Unknown
        // FF D8 FF — every JPEG variant (JFIF, Exif, raw).
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> Format.Jpeg
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Format.Png
        // "RIFF" .... "WEBP"
        bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes.matchesAt(8, 0x57, 0x45, 0x42, 0x50) -> Format.Webp
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> Format.Gif
        bytes.startsWith(0x42, 0x4D) -> Format.Bmp
        else -> Format.Unknown
    }

    /**
     * Pairs the cover with the correct filename for its real content.
     *
     * @param baseName the ROM's name without extension; the image extension is chosen here.
     */
    fun prepare(baseName: String, bytes: ByteArray): Prepared = when (val format = detect(bytes)) {
        Format.Png -> {
            Log.i(TAG, "Cover is really PNG despite its declared type; saving as .png")
            Prepared("$baseName.png", bytes, MIME_PNG)
        }
        Format.Jpeg -> Prepared("$baseName.jpg", bytes, MIME_JPEG)
        // Renaming these would not help a frontend that cannot decode them, so this is
        // the one case where the picture is genuinely converted.
        else -> convertToJpeg(baseName, bytes, format)
    }

    private fun convertToJpeg(baseName: String, bytes: ByteArray, format: Format): Prepared {
        val bitmap = decode(bytes)
        if (bitmap == null) {
            // Undecodable bytes are still written out: a cover that some frontends can
            // read beats no cover, and the scan carries on either way.
            Log.w(TAG, "Cover is ${format.displayName} and could not be decoded; saving as received")
            return Prepared("$baseName.jpg", bytes, MIME_JPEG)
        }
        return try {
            val encoded = compressToJpeg(bitmap)
            if (encoded == null) {
                Prepared("$baseName.jpg", bytes, MIME_JPEG)
            } else {
                Log.i(TAG, "Converted ${format.displayName} cover to JPEG (${bytes.size} -> ${encoded.size} bytes)")
                Prepared("$baseName.jpg", encoded, MIME_JPEG)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes to a bitmap, scaling anything unreasonably large down first.
     *
     * Box art is well under the cap, so this only guards against a source returning
     * something enormous — running out of memory here would take the whole scan down.
     */
    private fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (largestSide <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(largestSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "Not enough memory to decode this cover")
        null
    } catch (error: Exception) {
        Log.w(TAG, "Could not decode cover: ${error.javaClass.simpleName}")
        null
    }

    private fun sampleSizeFor(largestSide: Int): Int {
        var sample = 1
        while (largestSide / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray? = try {
        // JPEG has no alpha channel, so transparent areas would come out black.
        // Drawing onto white first keeps cut-out covers looking right.
        val source = if (bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
        val stream = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val ok = source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (source !== bitmap) source.recycle()
        if (ok) stream.toByteArray() else null
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "Not enough memory to convert this cover")
        null
    } catch (error: Exception) {
        Log.w(TAG, "Could not convert cover: ${error.javaClass.simpleName}")
        null
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flattened).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flattened
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean = matchesAt(0, *signature)

    private fun ByteArray.matchesAt(offset: Int, vararg signature: Int): Boolean {
        if (size < offset + signature.size) return false
        signature.forEachIndexed { index, expected ->
            if (this[offset + index] != expected.toByte()) return false
        }
        return true
    }

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"

    private const val TAG = "ArtworkImage"
    private const val JPEG_QUALITY = 92
    private const val MAX_DIMENSION = 2048
    private const val MIN_SIGNATURE_BYTES = 12
    private const val INITIAL_BUFFER_BYTES = 512 * 1024
}
