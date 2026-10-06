package com.papr.scanner.pdf

import kotlin.math.roundToInt

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

/**
 * A page's pixel size after applying [scale] (used to hit a compression
 * target), never shrinking its shorter side below [minDimension] and never
 * enlarging it. Both sides scale by the same factor, so a floor can't squash
 * the page out of shape.
 */
internal fun scaledSize(width: Int, height: Int, scale: Float, minDimension: Int): Pair<Int, Int> {
    val floor = minDimension / minOf(width, height).toFloat()
    val factor = maxOf(scale, floor).coerceAtMost(1f)
    return (width * factor).roundToInt() to (height * factor).roundToInt()
}

/**
 * Each page's PDF box, given every page's image size (width to height): pages of the same
 * orientation share one size, the largest of them, so a multi-page scan gets same-size pages
 * even though each photo's pixel size differs. Portrait and landscape pages get separate sizes,
 * so a document mixing the two never turns every page into a square. A square page counts as
 * portrait.
 */
internal fun pageBoxesByOrientation(imageSizes: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    fun isLandscape(size: Pair<Int, Int>) = size.first > size.second
    fun largest(sizes: List<Pair<Int, Int>>) =
        (sizes.maxOfOrNull { it.first } ?: 1) to (sizes.maxOfOrNull { it.second } ?: 1)
    val (landscape, portrait) = imageSizes.partition(::isLandscape)
    val landscapeBox = largest(landscape)
    val portraitBox = largest(portrait)
    return imageSizes.map { if (isLandscape(it)) landscapeBox else portraitBox }
}
