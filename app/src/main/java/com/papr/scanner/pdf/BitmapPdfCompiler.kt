package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BitmapPdfCompiler {

    suspend fun compile(
        pageFiles: List<File>,
        outputFile: File,
        targetSizeBytes: Long? = null,
        // pageWidthPx/pageHeightPx is the shared page size (see below) - callers
        // that don't draw through the given Canvas (e.g. raw PDF content-stream
        // injection, which bypasses Canvas's own transform) need it to place
        // content relative to the actual page rather than this page's own,
        // possibly smaller/off-center, bitmap.
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int, pageWidthPx: Int, pageHeightPx: Int) -> Unit =
            { _, _, _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // PdfDocument gives no direct control over embedded image size or
        // quality - the only lever that actually changes the written file's
        // size is the pixel dimensions of what's drawn on each page. So
        // hitting a target size means actually building the PDF, measuring
        // its real size, and - if it's over - shrinking every page's
        // dimensions and rebuilding, rather than guessing from an
        // encoded-byte-count proxy that has no reliable relationship to what
        // PdfDocument actually writes.
        var scale = 1f
        for (attempt in 0 until MAX_ATTEMPTS) {
            writePdf(pageFiles, outputFile, scale, onPageDrawn)
            // Written as two direct checks (not one `val` combining them) so the
            // compiler can still smart-cast targetSizeBytes to non-null below -
            // smart-casting doesn't see through an intermediate Boolean val.
            if (targetSizeBytes == null || outputFile.length() <= targetSizeBytes) break
            if (attempt == MAX_ATTEMPTS - 1) break
            scale = nextScale(scale, targetSizeBytes, outputFile.length())
        }
        outputFile
    }

    private suspend fun writePdf(
        pageFiles: List<File>,
        outputFile: File,
        scale: Float,
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int, pageWidthPx: Int, pageHeightPx: Int) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            // All pages share one physical page size (the largest decoded
            // page, post-downsample-cap, post-target-scale) and are centered
            // within it, so a multi-page scan of e.g. a book doesn't end up
            // with a differently-sized PDF page per page just because each
            // scan's pixel dimensions differ slightly.
            val (pageWidthPx, pageHeightPx) = uniformPageSizePx(pageFiles, scale)
            val pageWidthPt = (pageWidthPx * POINTS_PER_PIXEL).roundToInt()
            val pageHeightPt = (pageHeightPx * POINTS_PER_PIXEL).roundToInt()
            pageFiles.forEachIndexed { index, pageFile ->
                val bitmap = decodeScaled(pageFile, scale)
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, index + 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.scale(POINTS_PER_PIXEL, POINTS_PER_PIXEL)
                // Center this page's (possibly smaller than the shared size)
                // bitmap. Anything the caller draws through this same Canvas
                // (e.g. the signature) is automatically kept aligned with the
                // image by this translate; anything NOT drawn through Canvas
                // needs pageWidthPx/pageHeightPx (passed below) to place
                // itself relative to the page instead.
                page.canvas.translate(
                    (pageWidthPx - bitmap.width) / 2f,
                    (pageHeightPx - bitmap.height) / 2f,
                )
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                onPageDrawn(page.canvas, bitmap, index, pageWidthPx, pageHeightPx)
                document.finishPage(page)
                bitmap.recycle()
            }
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** The largest post-downsample-cap, post-[scale] page dimensions across the document, used as the uniform PDF page size. */
    private fun uniformPageSizePx(pageFiles: List<File>, scale: Float): Pair<Int, Int> {
        var maxWidth = 1
        var maxHeight = 1
        for (pageFile in pageFiles) {
            val bounds = decodeBounds(pageFile)
            val sampleSize = downsampleFactorFor(bounds)
            maxWidth = maxOf(maxWidth, bounds.outWidth / sampleSize)
            maxHeight = maxOf(maxHeight, bounds.outHeight / sampleSize)
        }
        return scaledDimension(maxWidth, scale) to scaledDimension(maxHeight, scale)
    }

    /** Caps decoded page resolution so a multi-page export of very high-res scans can't exhaust the heap, then applies [scale] on top (used to hit a compression target). */
    private fun decodeScaled(pageFile: File, scale: Float): Bitmap {
        val downsampled = decodeDownsampled(pageFile)
        if (scale >= 1f) return downsampled
        val scaled = Bitmap.createScaledBitmap(
            downsampled,
            scaledDimension(downsampled.width, scale),
            scaledDimension(downsampled.height, scale),
            true,
        )
        if (scaled !== downsampled) downsampled.recycle()
        return scaled
    }

    private fun decodeDownsampled(pageFile: File): Bitmap {
        val sampleSize = downsampleFactorFor(decodeBounds(pageFile))
        return BitmapFactory.decodeFile(
            pageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Failed to decode bitmap: ${pageFile.absolutePath}")
    }

    private fun decodeBounds(pageFile: File): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(pageFile.absolutePath, it) }

    private fun downsampleFactorFor(bounds: BitmapFactory.Options): Int {
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        return if (maxDimension > MAX_PAGE_DIMENSION) maxOf(1, maxDimension / MAX_PAGE_DIMENSION) else 1
    }

    private fun scaledDimension(dimension: Int, scale: Float): Int =
        (dimension * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)

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

        /** Longest-side cap on decoded page bitmaps, to bound peak memory during multi-page export. */
        private const val MAX_PAGE_DIMENSION = 3000

        /** How many times [compile] will rebuild the whole document while searching for a target size. */
        private const val MAX_ATTEMPTS = 6

        /** Floor on any page dimension, so an unreasonably small target can't shrink a page to nothing. */
        private const val MIN_DIMENSION = 300
    }
}

/** Raster output size scales roughly with pixel area, so scaling by sqrt(target/actual) converges in a few rounds instead of a fixed-step search. */
internal fun nextScale(currentScale: Float, targetBytes: Long, actualBytes: Long): Float =
    currentScale * sqrt(targetBytes.toDouble() / actualBytes).toFloat()
