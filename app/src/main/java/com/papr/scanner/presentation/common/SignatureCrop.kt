package com.papr.scanner.presentation.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The part of the drawing canvas worth keeping for a signature: the bounding
 * box of every stroke point, grown by [padding] (so round stroke caps aren't
 * clipped) and clamped to the canvas. Cropping means the placed signature is
 * just the ink - not the ink plus however much empty canvas surrounded it.
 */
internal fun signatureCropRect(strokes: List<List<Offset>>, padding: Float, canvasWidth: Int, canvasHeight: Int): IntRect? {
    val points = strokes.flatten()
    if (points.isEmpty()) return null
    val left = floor(points.minOf { it.x } - padding).toInt().coerceAtLeast(0)
    val top = floor(points.minOf { it.y } - padding).toInt().coerceAtLeast(0)
    val right = ceil(points.maxOf { it.x } + padding).toInt().coerceAtMost(canvasWidth)
    val bottom = ceil(points.maxOf { it.y } + padding).toInt().coerceAtMost(canvasHeight)
    if (right <= left || bottom <= top) return null
    return IntRect(left, top, right, bottom)
}

/**
 * Strokes drawn on a pad of size [from], made to fit a pad of size [to] - the pad changes width
 * when the phone turns mid-signature. Shrunk evenly so the signature keeps its shape; a pad that
 * grew needs nothing.
 */
internal fun strokesFittedToPad(strokes: List<List<Offset>>, from: IntSize, to: IntSize): List<List<Offset>> {
    if (from.width <= 0 || from.height <= 0) return strokes
    val scale = minOf(to.width.toFloat() / from.width, to.height.toFloat() / from.height)
    if (scale >= 1f) return strokes
    return strokes.map { stroke -> stroke.map { it * scale } }
}
