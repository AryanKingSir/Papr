package com.papr.scanner.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    private const val THUMBNAIL_MAX_DIMENSION = 400
    private const val JPEG_QUALITY = 90

    fun documentDir(context: Context, documentId: Long): File =
        File(context.filesDir, "documents/$documentId").apply { mkdirs() }

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

    /** Rotates a page image file in place; downstream consumers (PDF export, thumbnails) just re-read it. */
    fun rotateJpeg(file: File, degrees: Float) {
        val original = BitmapFactory.decodeFile(file.absolutePath)
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        rotated.recycle()
        if (rotated !== original) original.recycle()
    }

    /** Generates a small JPEG thumbnail from a page image, overwriting any previous one for this document. */
    fun generateThumbnail(context: Context, documentId: Long, sourcePageFile: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePageFile.absolutePath, bounds)
        val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / THUMBNAIL_MAX_DIMENSION)
        val bitmap = BitmapFactory.decodeFile(
            sourcePageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
        val destination = thumbnailFile(context, documentId)
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return destination
    }
}
