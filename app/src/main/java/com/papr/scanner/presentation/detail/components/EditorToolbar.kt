package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One labelled tool: an icon over its name, so nothing on the bar has to be
 * guessed. [selected] shows the active tool with a filled pill behind the icon;
 * [locked] adds a small lock for tools that need Pro.
 */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    locked: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (locked) stateDescription = "Needs Papr Pro" }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(if (selected) colors.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onSecondaryContainer else contentColor)
            if (locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 8.dp)
                        .size(12.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

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

/** The groups bar: each entry takes an equal share of the width, like a navigation bar. */
@Composable
fun MainToolbar(vertical: Boolean, content: @Composable (Modifier) -> Unit) {
    if (vertical) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content(Modifier) }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(Modifier.weight(1f)) }
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

private const val DISABLED_ALPHA = 0.38f
