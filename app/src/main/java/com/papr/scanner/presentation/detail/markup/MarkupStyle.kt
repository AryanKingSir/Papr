package com.papr.scanner.presentation.detail.markup

import com.papr.scanner.presentation.detail.MarkupTool

enum class MarkupSize { SMALL, MEDIUM, LARGE }

/** The colour (ARGB) and size a tool draws with; kept per tool for the editing session. */
data class ToolStyle(val color: Int, val size: MarkupSize = MarkupSize.MEDIUM)

data class InkColor(val name: String, val argb: Int)

/** Pen and text share dark inks that read on white paper; the highlighter gets bright ones that stay see-through. */
val InkColors = listOf(
    InkColor("Black", 0xFF1B1B1F.toInt()),
    InkColor("Blue", 0xFF1A5FD0.toInt()),
    InkColor("Red", 0xFFC62828.toInt()),
    InkColor("Green", 0xFF2E7D32.toInt()),
)

val HighlighterColors = listOf(
    InkColor("Yellow", 0xFFFFE14D.toInt()),
    InkColor("Green", 0xFF8BE38B.toInt()),
    InkColor("Pink", 0xFFFF8AC9.toInt()),
    InkColor("Blue", 0xFF7FC8FF.toInt()),
)

fun MarkupTool.colors(): List<InkColor> = if (this == MarkupTool.HIGHLIGHTER) HighlighterColors else InkColors

fun MarkupTool.defaultStyle() = ToolStyle(colors().first().argb)

/** Line width or text height for [size], as a fraction of the page's shorter side. */
fun MarkupTool.sizeFraction(size: MarkupSize): Float = when (this) {
    MarkupTool.HIGHLIGHTER -> when (size) {
        MarkupSize.SMALL -> 0.02f
        MarkupSize.MEDIUM -> 0.035f
        MarkupSize.LARGE -> 0.05f
    }
    MarkupTool.TEXT -> when (size) {
        MarkupSize.SMALL -> 0.03f
        MarkupSize.MEDIUM -> 0.045f
        MarkupSize.LARGE -> 0.065f
    }
    else -> when (size) {
        MarkupSize.SMALL -> 0.003f
        MarkupSize.MEDIUM -> 0.006f
        MarkupSize.LARGE -> 0.012f
    }
}

/** How a size is named on the bar: lines are thin/thick, text is small/large. */
fun MarkupTool.sizeName(size: MarkupSize): String = if (this == MarkupTool.TEXT) {
    when (size) {
        MarkupSize.SMALL -> "Small text"
        MarkupSize.MEDIUM -> "Medium text"
        MarkupSize.LARGE -> "Large text"
    }
} else {
    when (size) {
        MarkupSize.SMALL -> "Thin"
        MarkupSize.MEDIUM -> "Medium"
        MarkupSize.LARGE -> "Thick"
    }
}
