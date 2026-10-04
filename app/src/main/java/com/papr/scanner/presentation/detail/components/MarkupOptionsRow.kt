package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papr.scanner.presentation.detail.MarkupTool
import com.papr.scanner.presentation.detail.markup.MarkupSize
import com.papr.scanner.presentation.detail.markup.ToolStyle
import com.papr.scanner.presentation.detail.markup.colors
import com.papr.scanner.presentation.detail.markup.sizeName

/**
 * The selected markup tool's options above the tools: its colours and sizes
 * (each size drawn in the chosen ink, so you see the line you'll get), or a
 * hint for tools without options. Undo and redo sit at the end either way.
 */
@Composable
fun MarkupOptionsRow(
    tool: MarkupTool,
    style: ToolStyle?,
    hint: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onStyleChange: (ToolStyle) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tool.colors().forEach { ink ->
                    ColorSwatch(
                        color = Color(ink.argb),
                        name = ink.name,
                        selected = ink.argb == style.color,
                        onClick = { onStyleChange(style.copy(color = ink.argb)) },
                    )
                }
                VerticalDivider(modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = 1.dp, height = 28.dp))
                MarkupSize.entries.forEach { size ->
                    SizeChoice(
                        tool = tool,
                        option = size,
                        color = Color(style.color),
                        selected = size == style.size,
                        onClick = { onStyleChange(style.copy(size = size)) },
                    )
                }
            }
        } else {
            // Wraps rather than scrolls: a hint is only useful if it can be read in full.
            Text(
                text = hint.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    // A ring around the chosen colour rather than a check mark, so the ink itself stays visible.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .border(
                    BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    CircleShape,
                )
                .padding(4.dp)
                .clip(CircleShape)
                .background(color)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
        )
    }
}

@Composable
private fun SizeChoice(tool: MarkupTool, option: MarkupSize, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = tool.sizeName(option) },
        contentAlignment = Alignment.Center,
    ) {
        // A scrap of white paper, so each sample shows the ink as it'll look on the page - in either theme.
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (tool == MarkupTool.TEXT) {
                Text(
                    text = "A",
                    color = color,
                    fontWeight = FontWeight.Medium,
                    fontSize = when (option) {
                        MarkupSize.SMALL -> 11.sp
                        MarkupSize.MEDIUM -> 15.sp
                        MarkupSize.LARGE -> 20.sp
                    },
                )
            } else {
                val highlighter = tool == MarkupTool.HIGHLIGHTER
                val lineWidth = when (option) {
                    MarkupSize.SMALL -> if (highlighter) 5.dp else 1.5.dp
                    MarkupSize.MEDIUM -> if (highlighter) 9.dp else 3.dp
                    MarkupSize.LARGE -> if (highlighter) 13.dp else 6.dp
                }
                Canvas(modifier = Modifier.size(width = 20.dp, height = 16.dp)) {
                    drawLine(
                        color = if (highlighter) color.copy(alpha = HIGHLIGHTER_PREVIEW_ALPHA) else color,
                        start = Offset(0f, this.size.height / 2f),
                        end = Offset(this.size.width, this.size.height / 2f),
                        strokeWidth = lineWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private const val HIGHLIGHTER_PREVIEW_ALPHA = 0.6f
