package com.papr.scanner.presentation.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Like detectDragGestures, except a stroke starts where the finger went down, not where it
 * crossed the touch slop - otherwise the first few millimetres of every stroke go missing.
 * The slop still decides whether a touch is a drag, so a tap stays a tap. With [drawDots], a tap
 * becomes a two-point stroke on the same spot (the dot on an "i").
 *
 * [onStroke] gets the whole stroke so far after every move.
 */
internal suspend fun PointerInputScope.detectStrokes(
    drawDots: Boolean,
    onStroke: (List<Offset>) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        if (start == null) {
            val lifted = currentEvent.changes.none { it.id == down.id && it.pressed }
            if (drawDots && lifted) {
                onStroke(listOf(down.position, down.position))
                onEnd()
            }
            return@awaitEachGesture
        }
        var points = listOf(down.position, start.position)
        onStroke(points)
        val finished = drag(start.id) { change ->
            change.consume()
            points = points + change.position
            onStroke(points)
        }
        if (finished) onEnd() else onCancel()
    }
}
