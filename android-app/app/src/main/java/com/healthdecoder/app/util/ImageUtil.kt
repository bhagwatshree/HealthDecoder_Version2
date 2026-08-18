package com.healthdecoder.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Prepares page images for scanning. Camera photos are 5–10 MB each; sending several of
 * them (plus their Base64 copies for the AI request) used to exhaust the app's memory
 * and crash multi-document scans. Downscaling to ~[MAX_DIMENSION]px JPEG keeps documents
 * perfectly readable for OCR at roughly a tenth of the size.
 */
object ImageUtil {

    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    /**
     * Reads one page image and returns it downscaled to at most [MAX_DIMENSION]px on its
     * longest side, EXIF rotation applied, re-encoded as JPEG. Non-image content returns
     * null. Never throws — returns null on any failure (including out-of-memory).
     */
    fun compressForScan(context: Context, uri: Uri): ByteArray? {
        // Pass 1: bounds only, to pick a power-of-two downsample factor. decodeStream always
        // returns null in this mode (that's how bounds-only decoding works) — check the
        // stream itself for failure, not the decode result, or every image "fails" to read.
        val bounds = try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { opts ->
                stream.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            e.printStackTrace(); return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2

        // On memory-constrained devices even a downsampled decode can OOM (RGB_565 halves
        // the per-pixel cost vs the default ARGB_8888); retry progressively smaller before
        // giving up, rather than failing the whole page on the first attempt.
        repeat(4) { attempt ->
            try {
                return decodeAndCompress(context, uri, sample)
            } catch (e: OutOfMemoryError) {
                sample *= 2
            } catch (e: Exception) {
                e.printStackTrace(); return null
            }
        }
        return null
    }

    private fun decodeAndCompress(context: Context, uri: Uri, sample: Int): ByteArray? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // Camera photos carry their rotation in EXIF; re-encoding drops EXIF, so
        // bake the rotation into the pixels or pages would display sideways.
        val degrees = exifRotation(context, uri)
        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) }, true
            )
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun exifRotation(context: Context, uri: Uri): Float = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }
}
