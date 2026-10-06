package com.papr.scanner.domain.model

import kotlin.math.abs

data class NormalizedPoint(val x: Float, val y: Float)

/** A confidential mark's standard thickness, as a fraction of the page image's width. */
const val DEFAULT_CONFIDENTIAL_MARK_WIDTH = 0.04f

/**
 * One freeform confidential-mark stroke, points normalized 0f..1f relative to the page image's
 * own width/height. [width] is its thickness as a fraction of the page width; null means
 * [DEFAULT_CONFIDENTIAL_MARK_WIDTH]. Only a rotated page sets it, so a mark keeps covering
 * exactly what it covered when the page's width changes.
 */
data class ConfidentialMarkStroke(val points: List<NormalizedPoint>, val width: Float? = null)

val ConfidentialMarkStroke.widthFraction: Float get() = width ?: DEFAULT_CONFIDENTIAL_MARK_WIDTH

/**
 * Strokes joined by "|", points within a stroke by ";", each point "x,y" - e.g.
 * "0.1,0.2;0.15,0.22|0.5,0.6". A stroke with its own width starts "w0.0533:".
 */
internal fun encodeMarkStrokes(strokes: List<ConfidentialMarkStroke>): String =
    strokes.joinToString("|") { stroke ->
        val points = stroke.points.joinToString(";") { "${it.x},${it.y}" }
        stroke.width?.let { "w$it:$points" } ?: points
    }

/** Inverse of [encodeMarkStrokes]. A null or blank string (no marks saved yet) decodes to an empty list. */
internal fun decodeMarkStrokes(raw: String?): List<ConfidentialMarkStroke> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split("|").map { strokeRaw ->
        val width = if (strokeRaw.startsWith("w")) strokeRaw.substring(1, strokeRaw.indexOf(':')).toFloat() else null
        val points = strokeRaw.substringAfter(':').split(";").map { pointRaw ->
            val (x, y) = pointRaw.split(",")
            NormalizedPoint(x.toFloat(), y.toFloat())
        }
        ConfidentialMarkStroke(points, width)
    }
}

/**
 * Follows a 90° clockwise rotation of the page image, so the mark keeps covering the same
 * content. [pageAspect] is the page's width / height *before* turning: the new page is as wide
 * as the old one was tall, so the same thickness is a different fraction of it.
 */
fun ConfidentialMarkStroke.rotatedClockwise(pageAspect: Float): ConfidentialMarkStroke {
    val newWidth = widthFraction * pageAspect
    return ConfidentialMarkStroke(
        points = points.map { NormalizedPoint(x = 1f - it.y, y = it.x) },
        // Back to "standard" once turned back to its original shape, so it stays as it was saved.
        width = newWidth.takeUnless { abs(it - DEFAULT_CONFIDENTIAL_MARK_WIDTH) < 0.0001f },
    )
}
