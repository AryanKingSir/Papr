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
    val maxWidth = minOf(1f, 1f / (aspectRatio * pageAspect))
    // A very tall, narrow signature can't reach the minimum width without overflowing the page
    // height; fitting the page wins then.
    val sized = copy(width = width.coerceIn(minOf(MIN_SIGNATURE_WIDTH, maxWidth), maxWidth))
    return sized.copy(
        x = x.coerceIn(0f, 1f - sized.width),
        y = y.coerceIn(0f, (1f - sized.heightFraction(pageAspect)).coerceAtLeast(0f)),
    )
}

/**
 * Follows a 90° clockwise page rotation. The signature stays upright (it's an
 * annotation, not page content) and keeps its size relative to the page content.
 * Its position is rotated as a fraction of the room it has to move in rather
 * than by its center: an upright, non-square signature doesn't always fit around
 * its rotated center, and clamping it back onto the page made it drift a little
 * further with every turn. This way corners map to corners, it never leaves the
 * page, and four turns return it exactly. [newPageAspect] is the page's
 * width / height *after* rotation.
 */
fun PageSignature.rotatedClockwise(newPageAspect: Float): PageSignature {
    val oldPageAspect = 1f / newPageAspect
    val u = fractionOfRoom(x, 1f - width)
    val v = fractionOfRoom(y, 1f - heightFraction(oldPageAspect))
    // The new page is as wide as the old one was tall, so the same physical width is a smaller fraction of it.
    val resized = copy(width = width * oldPageAspect)
    return resized.copy(
        x = (1f - v) * (1f - resized.width),
        y = u * (1f - resized.heightFraction(newPageAspect)),
    ).clampedTo(newPageAspect)
}

private fun fractionOfRoom(position: Float, room: Float): Float =
    if (room <= 0f) 0f else (position / room).coerceIn(0f, 1f)

/** Where a freshly drawn signature lands: the bottom-right corner, a third of the page wide. */
fun defaultSignaturePlacement(imagePath: String, aspectRatio: Float, pageAspect: Float): PageSignature {
    val unplaced = PageSignature(imagePath, x = 0f, y = 0f, width = DEFAULT_SIGNATURE_WIDTH, aspectRatio = aspectRatio)
    return unplaced.copy(
        x = 1f - DEFAULT_SIGNATURE_WIDTH - DEFAULT_MARGIN,
        // Margin scaled by the page's shape so it's the same physical distance from both edges.
        y = 1f - unplaced.heightFraction(pageAspect) - DEFAULT_MARGIN * pageAspect,
    ).clampedTo(pageAspect)
}
