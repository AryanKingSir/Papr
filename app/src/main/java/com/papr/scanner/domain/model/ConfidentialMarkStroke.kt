package com.papr.scanner.domain.model

data class NormalizedPoint(val x: Float, val y: Float)

/** One freeform confidential-mark stroke, points normalized 0f..1f relative to the page image's own width/height. */
data class ConfidentialMarkStroke(val points: List<NormalizedPoint>)

/** Strokes joined by "|", points within a stroke by ";", each point "x,y" - e.g. "0.1,0.2;0.15,0.22|0.5,0.6". */
internal fun encodeMarkStrokes(strokes: List<ConfidentialMarkStroke>): String =
    strokes.joinToString("|") { stroke -> stroke.points.joinToString(";") { "${it.x},${it.y}" } }

/** Inverse of [encodeMarkStrokes]. A null or blank string (no marks saved yet) decodes to an empty list. */
internal fun decodeMarkStrokes(raw: String?): List<ConfidentialMarkStroke> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split("|").map { strokeRaw ->
        val points = strokeRaw.split(";").map { pointRaw ->
            val (x, y) = pointRaw.split(",")
            NormalizedPoint(x.toFloat(), y.toFloat())
        }
        ConfidentialMarkStroke(points)
    }
}

/** Follows a 90° clockwise rotation of the page image, so the mark keeps covering the same content. */
fun ConfidentialMarkStroke.rotatedClockwise(): ConfidentialMarkStroke =
    ConfidentialMarkStroke(points.map { NormalizedPoint(x = 1f - it.y, y = it.x) })
