package com.papr.scanner.pdf

import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.InkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import kotlin.math.sqrt

/** Fraction of the page's width used as the confidential-mark stroke's on-screen/export width. */
internal const val CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION = 0.04f

data class FittedBounds(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where an image of [imageWidth]x[imageHeight] lands inside a container of
 * [containerWidth]x[containerHeight], matching Compose's `ContentScale.Fit`
 * (default, [crop] = false - scaled down to fit entirely inside, centered,
 * letterboxed) or `ContentScale.Crop` ([crop] = true - scaled up to cover
 * the whole container, centered, overflow clipped by the caller).
 */
internal fun fittedImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    crop: Boolean = false,
): FittedBounds {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return FittedBounds(0f, 0f, 0f, 0f)
    }
    val widthScale = containerWidth / imageWidth
    val heightScale = containerHeight / imageHeight
    val scale = if (crop) maxOf(widthScale, heightScale) else minOf(widthScale, heightScale)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedBounds(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/** Whether [point] lies within [toleranceRadius] of any segment of [stroke], for tap-to-erase hit-testing. */
internal fun isPointNearStroke(point: NormalizedPoint, stroke: ConfidentialMarkStroke, toleranceRadius: Float): Boolean =
    isPointNearPolyline(point, stroke.points, toleranceRadius)

/**
 * Whether the touch at ([xPx], [yPx]) lands on [stroke] drawn on a [widthPx]x[heightPx]
 * page: within half its drawn width plus [slopPx] (room for a fingertip). Measured in
 * pixels, so a tall page doesn't make lines easier to hit sideways than up and down.
 */
internal fun isOnInkStroke(stroke: InkStroke, xPx: Float, yPx: Float, widthPx: Int, heightPx: Int, slopPx: Float): Boolean {
    val pixelPoints = stroke.points.map { NormalizedPoint(it.x * widthPx, it.y * heightPx) }
    val halfWidth = stroke.width * minOf(widthPx, heightPx) / 2f
    return isPointNearPolyline(NormalizedPoint(xPx, yPx), pixelPoints, halfWidth + slopPx)
}

private fun isPointNearPolyline(point: NormalizedPoint, points: List<NormalizedPoint>, toleranceRadius: Float): Boolean {
    if (points.isEmpty()) return false
    if (points.size == 1) return distance(point, points[0]) <= toleranceRadius
    return points.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= toleranceRadius }
}

private fun distance(a: NormalizedPoint, b: NormalizedPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun distanceToSegment(p: NormalizedPoint, a: NormalizedPoint, b: NormalizedPoint): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared == 0f) return distance(p, a)
    val t = (((p.x - a.x) * abx) + ((p.y - a.y) * aby)) / lengthSquared
    val clampedT = t.coerceIn(0f, 1f)
    val closest = NormalizedPoint(a.x + clampedT * abx, a.y + clampedT * aby)
    return distance(p, closest)
}
