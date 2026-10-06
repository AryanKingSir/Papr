package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.scale
import com.papr.scanner.core.sampleSizeFor
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A built PDF, and whether it came in at or under the requested size (always true when none was asked for). */
data class CompiledPdf(val file: File, val metTarget: Boolean)

class BitmapPdfCompiler {

    suspend fun compile(
        pageFiles: List<File>,
        outputFile: File,
        targetSizeBytes: Long? = null,
        // Draws onto a page's (mutable) bitmap before it's embedded, in the
        // bitmap's own pixel space - anything drawn here becomes part of the
        // page image itself, and shrinks along with it for a compression target.
        preparePage: (canvas: Canvas, bitmap: Bitmap, pageIndex: Int) -> Unit = { _, _, _ -> },
        // Called after the prepared image is drawn on the PDF page. Content that
        // isn't part of the image (e.g. raw PDF text operators) uses placement to
        // position itself relative to the image.
        onPageDrawn: suspend (bitmap: Bitmap, pageIndex: Int, placement: PagePlacement) -> Unit = { _, _, _ -> },
        // Post-processes each freshly written file (e.g. adds a text layer, encrypts)
        // before its size is measured, so a target size covers the finished file.
        finish: suspend (File) -> Unit = {},
    ): CompiledPdf = withContext(Dispatchers.IO) {
        // PdfDocument gives no direct control over embedded image size or
        // quality - the only lever that actually changes the written file's
        // size is the pixel dimensions of what's drawn on each page. So
        // hitting a target size means actually building the PDF, measuring
        // its real size, and - if it's over - shrinking every page's
        // dimensions and rebuilding, rather than guessing from an
        // encoded-byte-count proxy that has no reliable relationship to what
        // PdfDocument actually writes.
        var scale = 1f
        var previousSize = Long.MAX_VALUE
        for (attempt in 0 until MAX_ATTEMPTS) {
            writePdf(pageFiles, outputFile, scale, preparePage, onPageDrawn)
            finish(outputFile)
            val size = outputFile.length()
            if (targetSizeBytes == null || size <= targetSizeBytes) return@withContext CompiledPdf(outputFile, metTarget = true)
            // Pages bottom out at MIN_DIMENSION, and the text layer doesn't shrink at all; once a
            // rebuild stops getting smaller, more rebuilds only make the wait longer.
            if (attempt == MAX_ATTEMPTS - 1 || size >= previousSize) break
            previousSize = size
            scale = nextScale(scale, targetSizeBytes, size)
        }
        CompiledPdf(outputFile, metTarget = false)
    }

    private suspend fun writePdf(
        pageFiles: List<File>,
        outputFile: File,
        scale: Float,
        preparePage: (canvas: Canvas, bitmap: Bitmap, pageIndex: Int) -> Unit,
        onPageDrawn: suspend (bitmap: Bitmap, pageIndex: Int, placement: PagePlacement) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            // All pages share one physical page size - the largest decoded page -
            // and every page's image is fit-scaled into it, so a multi-page scan
            // of e.g. a book gets same-size pages with same-size content, even
            // though each scan's pixel dimensions differ with how far away the
            // camera was. The box is measured at full resolution, so compressing
            // lowers image resolution without shrinking the paper itself.
            val (pageWidthPx, pageHeightPx) = uniformPageSizePx(pageFiles)
            val pageWidthPt = (pageWidthPx * POINTS_PER_PIXEL).roundToInt()
            val pageHeightPt = (pageHeightPx * POINTS_PER_PIXEL).roundToInt()
            // PdfDocument keeps a copy of every page's pixels until writeTo, so it's the whole
            // document's pixel count that has to stay bounded, not just each page's.
            val maxPixelsPerPage = TOTAL_PIXEL_BUDGET / pageFiles.size.coerceAtLeast(1)
            pageFiles.forEachIndexed { index, pageFile ->
                val bitmap = decodeScaled(pageFile, scale, maxPixelsPerPage)
                try {
                    preparePage(Canvas(bitmap), bitmap, index)
                    // Computed from the bitmap actually decoded, not predicted
                    // dimensions, so a page can never overflow the box and be cropped.
                    val placement = fitPlacement(pageWidthPx, pageHeightPx, bitmap.width, bitmap.height)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, index + 1).create())
                    try {
                        page.canvas.scale(POINTS_PER_PIXEL, POINTS_PER_PIXEL)
                        page.canvas.translate(placement.offsetX, placement.offsetY)
                        page.canvas.scale(placement.scale, placement.scale)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, BITMAP_PAINT)
                    } finally {
                        // Always finish the page: close() below throws "Current page
                        // not finished" otherwise, replacing the real error with that one.
                        document.finishPage(page)
                    }
                    onPageDrawn(bitmap, index, placement)
                } finally {
                    bitmap.recycle()
                }
            }
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** The largest post-downsample-cap page dimensions across the document, used as the uniform PDF page size. */
    private fun uniformPageSizePx(pageFiles: List<File>): Pair<Int, Int> {
        var maxWidth = 1
        var maxHeight = 1
        for (pageFile in pageFiles) {
            val bounds = decodeBounds(pageFile)
            val sampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), MAX_PAGE_DIMENSION)
            maxWidth = maxOf(maxWidth, bounds.outWidth / sampleSize)
            maxHeight = maxOf(maxHeight, bounds.outHeight / sampleSize)
        }
        return maxWidth to maxHeight
    }

    /**
     * Decodes a page as a mutable bitmap (so preparePage can draw on it), capped
     * so a multi-page export of very high-res scans can't exhaust memory, then
     * shrunk by [scale] (used to hit a compression target).
     */
    private fun decodeScaled(pageFile: File, scale: Float, maxPixels: Long): Bitmap {
        val bounds = decodeBounds(pageFile)
        val sampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), MAX_PAGE_DIMENSION)
        val decoded = BitmapFactory.decodeFile(
            pageFile.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inMutable = true
            },
        ) ?: throw IOException("Failed to decode bitmap: ${pageFile.absolutePath}")
        val budgetScale = sqrt(maxPixels.toDouble() / (decoded.width.toLong() * decoded.height)).toFloat().coerceAtMost(1f)
        val (width, height) = scaledSize(decoded.width, decoded.height, scale * budgetScale, MIN_DIMENSION)
        if (width == decoded.width && height == decoded.height) return decoded
        val scaled = decoded.scale(width, height)
        decoded.recycle()
        return if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
    }

    private fun decodeBounds(pageFile: File): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(pageFile.absolutePath, it) }

    private companion object {
        /** Longest-side cap on decoded page bitmaps, to bound peak memory during multi-page export. */
        const val MAX_PAGE_DIMENSION = 3000

        /**
         * Pixels across all pages of one export (about 320 MB of native memory at 4 bytes each).
         * Up to about 12 full-size pages fit; a 30-page document still gets about 1900 x 1400
         * per page, plenty for text.
         */
        const val TOTAL_PIXEL_BUDGET = 80_000_000L

        /** How many times [compile] will rebuild the whole document while searching for a target size. */
        const val MAX_ATTEMPTS = 6

        /** Floor on a page's shorter side, so an unreasonably small target can't shrink a page to nothing. */
        const val MIN_DIMENSION = 300

        /** Smooths the image when it's scaled to fit the page box. */
        val BITMAP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/**
 * Raster output size scales roughly with pixel area, so scaling by sqrt(target/actual) converges in a few rounds instead of a fixed-step search.
 * It aims a little under the target: part of the file (the OCR text layer, encryption, PDF structure) doesn't shrink
 * with the images, and aiming exactly at the target would leave the file just over it every round.
 */
internal fun nextScale(currentScale: Float, targetBytes: Long, actualBytes: Long): Float =
    currentScale * sqrt(TARGET_AIM * targetBytes / actualBytes).toFloat()

private const val TARGET_AIM = 0.95
