package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BitmapPdfCompiler(private val compressionIterator: CompressionIterator = CompressionIterator()) {

    suspend fun compile(
        pageFiles: List<File>,
        outputFile: File,
        targetSizeBytes: Long? = null,
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int) -> Unit = { _, _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            val document = PdfDocument()
            try {
                val perPageTargetBytes = targetSizeBytes?.let { it / pageFiles.size }
                pageFiles.forEachIndexed { index, pageFile ->
                    var bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
                    if (perPageTargetBytes != null) {
                        bitmap = recompress(bitmap, perPageTargetBytes)
                    }
                    val pageWidthPt = (bitmap.width * POINTS_PER_PIXEL).roundToInt()
                    val pageHeightPt = (bitmap.height * POINTS_PER_PIXEL).roundToInt()
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, index + 1).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.scale(POINTS_PER_PIXEL, POINTS_PER_PIXEL)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    onPageDrawn(page.canvas, bitmap, index)
                    document.finishPage(page)
                    bitmap.recycle()
                }
                outputFile.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            outputFile
        }

    /** Best-effort: JPEG requantization per page, since PdfDocument gives no direct control over embedded image size. */
    private fun recompress(bitmap: Bitmap, targetBytes: Long): Bitmap {
        val compressed = compressionIterator.compress(targetBytes) { quality ->
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
        }
        val recompressed = BitmapFactory.decodeByteArray(compressed, 0, compressed.size)
        bitmap.recycle()
        return recompressed
    }

    companion object {
        /**
         * A scanned page's pixel dimensions can't be used directly as PDF point
         * dimensions - PdfDocument.PageInfo is in points (1/72 inch), so a
         * ~2000px-wide scan would otherwise become a ~28-inch-wide page,
         * appearing absurdly zoomed in to most viewers. Scale down assuming a
         * nominal 200 DPI capture, which lands page size in a normal
         * letter/A4-ish range regardless of a given scan's true resolution.
         */
        const val POINTS_PER_PIXEL: Float = 72f / 200f
    }
}
