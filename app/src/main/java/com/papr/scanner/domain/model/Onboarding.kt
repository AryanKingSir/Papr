package com.papr.scanner.domain.model

/** A one-time hint, shown the first time its screen or tool is reached and gone once dismissed. */
enum class Tip { HOME_SELECT, EDITOR_TOOLS, ORGANIZE_DRAG, MARKUP }

/** How far through the tour this install is: the welcome slides, and which tips have been dismissed. */
data class OnboardingState(val welcomeSeen: Boolean, val seenTips: Set<Tip>)
