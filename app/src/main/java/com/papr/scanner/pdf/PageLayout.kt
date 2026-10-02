package com.papr.scanner.pdf

/**
 * A scanned page's pixel dimensions can't be used directly as PDF point
 * dimensions - PdfDocument.PageInfo is in points (1/72 inch), so a ~2000px-wide
 * scan would otherwise become a ~28-inch-wide page, appearing absurdly zoomed
 * in to most viewers. Scale down assuming a nominal 200 DPI capture, which
 * lands page size in a normal letter/A4-ish range regardless of a given scan's
 * true resolution.
 */
const val POINTS_PER_PIXEL: Float = 72f / 200f

/**
 * Where one page's bitmap is drawn inside the document's shared page box
 * ([pageWidthPx] x [pageHeightPx]): scaled by [scale], then offset by
 * [offsetX]/[offsetY] - all in page-box pixels.
 */
data class PagePlacement(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
) {
    /** Maps an x in the drawn bitmap's own pixel space to PDF user space (points). */
    fun toPdfX(bitmapX: Float): Float = (offsetX + bitmapX * scale) * POINTS_PER_PIXEL

    /** Maps a top-down bitmap y to PDF's bottom-up user space (points). */
    fun toPdfY(bitmapY: Float): Float = (pageHeightPx - (offsetY + bitmapY * scale)) * POINTS_PER_PIXEL

    fun toPdfLength(bitmapLength: Float): Float = bitmapLength * scale * POINTS_PER_PIXEL
}

/**
 * Scales an image to fit entirely inside the page box (aspect preserved,
 * centered), up or down - so a scan photographed from slightly further away
 * still fills the same page as its neighbors instead of rendering smaller.
 */
internal fun fitPlacement(pageWidthPx: Int, pageHeightPx: Int, imageWidth: Int, imageHeight: Int): PagePlacement {
    val scale = minOf(pageWidthPx / imageWidth.toFloat(), pageHeightPx / imageHeight.toFloat())
    return PagePlacement(
        pageWidthPx = pageWidthPx,
        pageHeightPx = pageHeightPx,
        offsetX = (pageWidthPx - imageWidth * scale) / 2f,
        offsetY = (pageHeightPx - imageHeight * scale) / 2f,
        scale = scale,
    )
}
