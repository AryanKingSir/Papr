package com.papr.scanner.presentation.common

import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** The kinds of feedback Papr plays. Each falls back to the nearest older one on phones before Android 14. */
enum class Haptic(internal val constant: Int) {
    /** A page picked up to drag. */
    DRAG_START(HapticFeedbackConstantsCompat.DRAG_START),

    /** A dragged page passing another, which can happen many times a second. */
    TICK(HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK),

    /** A small step: a tool picked, undo, redo. */
    SEGMENT_TICK(HapticFeedbackConstantsCompat.SEGMENT_TICK),
    TOGGLE_ON(HapticFeedbackConstantsCompat.TOGGLE_ON),
    TOGGLE_OFF(HapticFeedbackConstantsCompat.TOGGLE_OFF),

    /** Something finished: saved, exported, merged, deleted. */
    CONFIRM(HapticFeedbackConstantsCompat.CONFIRM),

    /** Something didn't happen: an error, or a feature that needs Pro. */
    REJECT(HapticFeedbackConstantsCompat.REJECT),
}

/**
 * Plays [Haptic]s on [view] while the Haptics setting is on. The phone's own touch-feedback
 * setting still applies on top, so a phone with vibration off stays silent.
 */
class PaprHaptics(private val view: View?, private val enabled: () -> Boolean) {

    private val tickThrottle = TickThrottle()

    fun perform(haptic: Haptic) {
        val view = view ?: return
        if (!enabled()) return
        if (haptic == Haptic.TICK && !tickThrottle.tryTick(SystemClock.uptimeMillis())) return
        ViewCompat.performHapticFeedback(view, haptic.constant)
    }

    fun toggle(on: Boolean) = perform(if (on) Haptic.TOGGLE_ON else Haptic.TOGGLE_OFF)
}

/**
 * Lets through at most one tick per [minGapMillis]: a page dragged past the edge auto-scrolls the
 * grid and can pass several pages in a frame, which would otherwise feel like a buzz.
 */
internal class TickThrottle(private val minGapMillis: Long = 40) {
    private var lastTick = Long.MIN_VALUE

    fun tryTick(now: Long): Boolean {
        if (lastTick != Long.MIN_VALUE && now - lastTick < minGapMillis) return false
        lastTick = now
        return true
    }
}

/** Silent until MainActivity provides the real one, so previews and tests never vibrate. */
val LocalPaprHaptics = staticCompositionLocalOf { PaprHaptics(view = null, enabled = { false }) }

/** Compose's own haptics (a long-press, text selection handles), silenced when the Haptics setting is off. */
class GatedHapticFeedback(private val delegate: HapticFeedback, private val enabled: () -> Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) delegate.performHapticFeedback(hapticFeedbackType)
    }
}
