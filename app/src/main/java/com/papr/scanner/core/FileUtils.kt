package com.papr.scanner.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import com.papr.scanner.domain.model.Page
import com.papr.scanner.pdf.PageOverlayRenderer
import com.papr.scanner.pdf.overlays
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileUtils {

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    private const val THUMBNAIL_MAX_DIMENSION = 400
    private const val JPEG_QUALITY = 90
    private const val MAX_ROTATE_DIMENSION = 4000
    private const val MAX_FILENAME_LENGTH = 100

    // Characters invalid (or awkward) across Android's FUSE/FAT-derived storage,
    // plus control characters - anything else in a title is left alone.
    private val ILLEGAL_FILENAME_CHARS = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]")
    private val WHITESPACE_RUN = Regex("\\s+")

    fun documentDir(context: Context, documentId: Long): File =
        File(context.filesDir, "documents/$documentId").apply { mkdirs() }

    /** The one signature kept for reuse; only ever copied into a document, never referenced from one. */
    fun savedSignatureFile(context: Context): File =
        File(context.filesDir, "signature").apply { mkdirs() }.let { File(it, "saved_signature.png") }

    fun thumbnailFile(context: Context, documentId: Long): File =
        File(context.filesDir, "thumbnails").apply { mkdirs() }.let { File(it, "$documentId.jpg") }

    fun copyContentUriToFile(context: Context, source: Uri, destination: File) {
        context.contentResolver.openInputStream(source)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    fun getShareableUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
            file,
        )

    /** Turns a document title into a safe file base name (no extension), for use in exported/shared file names. */
    fun sanitizeFileName(title: String): String {
        val cleaned = title.trim().replace(ILLEGAL_FILENAME_CHARS, " ").replace(WHITESPACE_RUN, " ").trim()
        return cleaned.ifEmpty { "Document" }.take(MAX_FILENAME_LENGTH)
    }

    /** Rotates a page image file in place; downstream consumers (PDF export, thumbnails) just re-read it. */
    suspend fun rotateJpeg(file: File, degrees: Float) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), MAX_ROTATE_DIMENSION)
        val original = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Failed to decode bitmap: ${file.absolutePath}")
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        rotated.recycle()
        if (rotated !== original) original.recycle()
    }

    /**
     * Generates the document's small JPEG thumbnail from [page] (its first page),
     * overwriting any previous one. The page's signature and confidential marks
     * are baked into this cached thumbnail so a marked page never shows
     * unredacted in the home grid - the page image file itself is never touched,
     * only this derived cache artifact.
     */
    suspend fun generateThumbnail(context: Context, page: Page): File =
        withContext(Dispatchers.IO) {
            val sourcePageFile = File(page.imageUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePageFile.absolutePath, bounds)
            val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / THUMBNAIL_MAX_DIMENSION)
            // inMutable: overlays are drawn straight onto the decoded bitmap, and
            // Canvas() rejects the immutable bitmaps BitmapFactory returns by default.
            val bitmap = BitmapFactory.decodeFile(
                sourcePageFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inMutable = true
                },
            ) ?: throw IOException("Failed to decode bitmap: ${sourcePageFile.absolutePath}")
            val canvas = Canvas(bitmap)
            PageOverlayRenderer().draw(canvas, page.overlays(), bitmap.width, bitmap.height)
            val destination = thumbnailFile(context, page.documentId)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            destination
        }

    /** An image file's width / height, read from its header only (no pixel decode). */
    suspend fun imageAspectRatio(file: File): Float = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Failed to read image size: ${file.absolutePath}")
        bounds.outWidth / bounds.outHeight.toFloat()
    }

    /**
     * Decodes a bitmap capped at [maxDimension] on its longest side, to bound memory for a one-off read (e.g. OCR).
     * [mutable] for drawing onto the result (Canvas rejects the immutable bitmaps decoded by default).
     */
    suspend fun decodeDownsampled(file: File, maxDimension: Int, mutable: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxDimension)
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inMutable = mutable
            },
        ) ?: throw IOException("Failed to decode bitmap: ${file.absolutePath}")
    }
}
