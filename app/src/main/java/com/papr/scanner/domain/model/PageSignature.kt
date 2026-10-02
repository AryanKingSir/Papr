package com.papr.scanner.domain.model

/** Smallest width a signature can be resized to, as a fraction of the page width. */
const val MIN_SIGNATURE_WIDTH = 0.08f
private const val DEFAULT_SIGNATURE_WIDTH = 1f / 3f
private const val DEFAULT_MARGIN = 0.04f

/**
 * A signature placed on one page. [x]/[y] (its top-left corner) and [width] are
 * fractions of the page image's own width/height; [aspectRatio] is the
 * signature image's height / width, so layout never has to decode the image
 * just to know its shape.
 */
data class PageSignature(
    val imagePath: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val aspectRatio: Float,
)

/** The signature's height as a fraction of the page height, on a page whose width / height is [pageAspect]. */
fun PageSignature.heightFraction(pageAspect: Float): Float = width * aspectRatio * pageAspect

/** Keeps the signature a usable size and fully on the page. */
fun PageSignature.clampedTo(pageAspect: Float): PageSignature {
    val maxWidthForHeight = 1f / (aspectRatio * pageAspect)
    val sized = copy(width = width.coerceIn(MIN_SIGNATURE_WIDTH, minOf(1f, maxWidthForHeight)))
    return sized.copy(
        x = x.coerceIn(0f, 1f - sized.width),
        y = y.coerceIn(0f, (1f - sized.heightFraction(pageAspect)).coerceAtLeast(0f)),
    )
}

/**
 * Follows a 90° clockwise page rotation: the signature's center rotates with
 * the page, while the signature itself stays upright (it's an annotation, not
 * page content) and keeps its width as a fraction of the new page width.
 * [newPageAspect] is the page's width / height *after* rotation.
 */
fun PageSignature.rotatedClockwise(newPageAspect: Float): PageSignature {
    val centerX = x + width / 2f
    val centerY = y + heightFraction(1f / newPageAspect) / 2f
    return copy(
        x = (1f - centerY) - width / 2f,
        y = centerX - heightFraction(newPageAspect) / 2f,
    ).clampedTo(newPageAspect)
}

/** Where a freshly drawn signature lands: the bottom-right corner, a third of the page wide. */
fun defaultSignaturePlacement(imagePath: String, aspectRatio: Float, pageAspect: Float): PageSignature {
    val unplaced = PageSignature(imagePath, x = 0f, y = 0f, width = DEFAULT_SIGNATURE_WIDTH, aspectRatio = aspectRatio)
    return unplaced.copy(
        x = 1f - DEFAULT_SIGNATURE_WIDTH - DEFAULT_MARGIN,
        // Margin scaled by the page's shape so it's the same physical distance from both edges.
        y = 1f - unplaced.heightFraction(pageAspect) - DEFAULT_MARGIN * pageAspect,
    ).clampedTo(pageAspect)
}
