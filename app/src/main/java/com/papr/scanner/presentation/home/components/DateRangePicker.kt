package com.papr.scanner.presentation.home.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.papr.scanner.presentation.home.localDayRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Picks the range of scan dates to show; a start with no end is a single day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(onRangeSelected: (LongRange) -> Unit, onDismiss: () -> Unit) {
    val dateRangePickerState = rememberDateRangePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = dateRangePickerState.selectedStartDateMillis
            TextButton(
                onClick = {
                    start?.let { onRangeSelected(localDayRange(it, dateRangePickerState.selectedEndDateMillis, TimeZone.getDefault())) }
                    onDismiss()
                },
                enabled = start != null,
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(
            state = dateRangePickerState,
            showModeToggle = false,
            // A cap rather than a fixed height, so the dialog shrinks to fit a landscape screen.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
        )
    }
}

/** "Mar 1 - Mar 31", or just "Mar 1" for a single day. */
fun dateRangeLabel(range: LongRange): String {
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val start = formatter.format(Date(range.first))
    val end = formatter.format(Date(range.last))
    return if (start == end) start else "$start - $end"
}
