package com.papr.scanner.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Free markup drawn on a page: kept editable per page, and baked into the page
 * image only when exporting. Positions are normalized 0f..1f relative to the page
 * image's own width/height; sizes are fractions of the image's shorter side, so a
 * rotate (which swaps width and height) doesn't change how thick anything looks.
 */
sealed interface PageAnnotation {
    /** Follows a 90° clockwise rotation of the page image, so it stays on the same content. */
    fun rotatedClockwise(): PageAnnotation
}

enum class InkTool { PEN, HIGHLIGHTER }

/** A freehand pen or highlighter line. [color] is ARGB; the highlighter's translucency is applied when drawn. */
data class InkStroke(
    val tool: InkTool,
    val color: Int,
    val width: Float,
    val points: List<NormalizedPoint>,
) : PageAnnotation {
    override fun rotatedClockwise() = copy(points = points.map { it.rotatedClockwise() })
}

/**
 * Typed text whose top-left corner sits at ([x], [y]). [size] is the text height;
 * [quarterTurns] is how many times the page was turned clockwise since the note
 * was placed, so the text turns along with the content it was written next to.
 */
data class TextNote(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val size: Float,
    val quarterTurns: Int = 0,
) : PageAnnotation {
    override fun rotatedClockwise(): TextNote {
        val anchor = NormalizedPoint(x, y).rotatedClockwise()
        return copy(x = anchor.x, y = anchor.y, quarterTurns = (quarterTurns + 1) % 4)
    }
}

private fun NormalizedPoint.rotatedClockwise() = NormalizedPoint(x = 1f - y, y = x)

/** JSON, unlike the marks' hand-rolled format, because a note's text can contain any character. Empty encodes to null. */
internal fun encodeAnnotations(annotations: List<PageAnnotation>): String? {
    if (annotations.isEmpty()) return null
    val array = JSONArray()
    for (annotation in annotations) {
        array.put(
            when (annotation) {
                is InkStroke -> JSONObject()
                    .put("type", "ink")
                    .put("tool", annotation.tool.name)
                    .put("color", annotation.color)
                    .put("width", annotation.width.toDouble())
                    .put("points", JSONArray().apply {
                        annotation.points.forEach { put(it.x.toDouble()).put(it.y.toDouble()) }
                    })
                is TextNote -> JSONObject()
                    .put("type", "text")
                    .put("text", annotation.text)
                    .put("x", annotation.x.toDouble())
                    .put("y", annotation.y.toDouble())
                    .put("color", annotation.color)
                    .put("size", annotation.size.toDouble())
                    .put("turns", annotation.quarterTurns)
            }
        )
    }
    return array.toString()
}

/** Inverse of [encodeAnnotations]. Kinds added by a newer version are skipped rather than failing the page. */
internal fun decodeAnnotations(raw: String?): List<PageAnnotation> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = JSONArray(raw)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        when (item.getString("type")) {
            "ink" -> {
                val flat = item.getJSONArray("points")
                InkStroke(
                    tool = InkTool.valueOf(item.getString("tool")),
                    color = item.getInt("color"),
                    width = item.getDouble("width").toFloat(),
                    points = (0 until flat.length() / 2).map { point ->
                        NormalizedPoint(flat.getDouble(point * 2).toFloat(), flat.getDouble(point * 2 + 1).toFloat())
                    },
                )
            }
            "text" -> TextNote(
                text = item.getString("text"),
                x = item.getDouble("x").toFloat(),
                y = item.getDouble("y").toFloat(),
                color = item.getInt("color"),
                size = item.getDouble("size").toFloat(),
                quarterTurns = item.getInt("turns"),
            )
            else -> null
        }
    }
}
