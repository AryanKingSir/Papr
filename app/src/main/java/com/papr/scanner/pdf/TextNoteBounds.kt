package com.papr.scanner.pdf

/** A text note's box relative to its anchor, in pixels: x from [minX] to [maxX], y from [minY] to [maxY]. */
data class NoteBox(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

/**
 * Where text [textWidth] x [textHeight] lies around its anchor once turned [quarterTurns]
 * clockwise about it - the way [PageOverlayRenderer] draws a note on a rotated page.
 */
internal fun noteBoxAroundAnchor(textWidth: Float, textHeight: Float, quarterTurns: Int): NoteBox =
    when (Math.floorMod(quarterTurns, 4)) {
        1 -> NoteBox(-textHeight, 0f, 0f, textWidth)
        2 -> NoteBox(-textWidth, 0f, -textHeight, 0f)
        3 -> NoteBox(0f, textHeight, -textWidth, 0f)
        else -> NoteBox(0f, textWidth, 0f, textHeight)
    }

/**
 * The anchor position nearest [anchor] at which a box spanning [boxStart]..[boxEnd] around it
 * stays within 0..[pageLength] - anything drawn past the page's edge is cut off in exports. A box
 * longer than the page starts at the near edge.
 */
internal fun anchorKeepingBoxOnPage(anchor: Float, boxStart: Float, boxEnd: Float, pageLength: Float): Float {
    val lowest = 0f - boxStart
    val highest = pageLength - boxEnd
    return if (highest < lowest) lowest else anchor.coerceIn(lowest, highest)
}
