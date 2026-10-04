package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.papr.scanner.presentation.common.ToolButton

/**
 * Lays tools out along the bar: spread evenly across the width when they fit,
 * scrolling sideways when they don't (narrow phones, the largest text size)
 * instead of squashing the last ones. [vertical] stacks them for the landscape rail.
 */
@Composable
fun ToolStrip(vertical: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (vertical) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    } else {
        BoxWithConstraints(modifier = modifier) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = maxWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    }
}

/** A group's tools with a way back to the groups: Done, a divider, then the tools. */
@Composable
fun SubToolbar(vertical: Boolean, onDone: () -> Unit, tools: @Composable () -> Unit) {
    val done = @Composable { ToolButton(Icons.Filled.Check, "Done", onDone) }
    if (vertical) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            done()
            HorizontalDivider(modifier = Modifier
                .width(48.dp)
                .padding(vertical = 4.dp))
            ToolStrip(vertical = true, modifier = Modifier.weight(1f), content = tools)
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            done()
            VerticalDivider(modifier = Modifier
                .height(40.dp)
                .padding(horizontal = 4.dp))
            ToolStrip(vertical = false, modifier = Modifier.weight(1f), content = tools)
        }
    }
}

/** An eraser drawn to match the outlined icon set, which has none. */
val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // The eraser's body, tilted, with the band between its tip and sleeve.
            moveTo(4f, 15f)
            lineTo(13f, 6f)
            lineTo(19f, 12f)
            lineTo(10f, 21f)
            close()
            moveTo(8.5f, 10.5f)
            lineTo(14.5f, 16.5f)
            // What it rubs along.
            moveTo(10f, 21f)
            lineTo(20f, 21f)
        }
        .build()
}

