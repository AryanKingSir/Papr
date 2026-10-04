package com.papr.scanner.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private val SHAPE = RoundedCornerShape(7.dp)

/**
 * The select box drawn over a document or page thumbnail: a squircle on its own.
 * Unselected it's a white outline over a faint dark tint, so it shows on white
 * and coloured pages alike; selected it's filled with the theme colour and checked.
 */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(SHAPE)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = UNSELECTED_TINT))
            .then(if (selected) Modifier else Modifier.border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), SHAPE))
            .clearAndSetSemantics { stateDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val UNSELECTED_TINT = 0.25f
