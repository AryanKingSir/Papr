package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.papr.scanner.presentation.detail.ExportSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    isExporting: Boolean,
    onExport: (ExportSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var ocrEnabled by remember { mutableStateOf(false) }
    var compressEnabled by remember { mutableStateOf(false) }
    var targetSizeMb by remember { mutableFloatStateOf(1f) }
    var passwordEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Export options", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

            OptionRow(
                title = "Searchable text (OCR)",
                subtitle = "Recognize text so it can be selected and searched",
                checked = ocrEnabled,
                onCheckedChange = { ocrEnabled = it },
            )
            OptionRow(
                title = "Compress to target size",
                subtitle = "Reduce image quality to hit a target file size",
                checked = compressEnabled,
                onCheckedChange = { compressEnabled = it },
            )
            if (compressEnabled) {
                Text("Target: ${"%.1f".format(targetSizeMb)} MB", modifier = Modifier.padding(top = 4.dp))
                Slider(
                    value = targetSizeMb,
                    onValueChange = { targetSizeMb = it },
                    valueRange = 0.2f..10f,
                )
            }
            OptionRow(
                title = "Password protect",
                subtitle = "Require a password to open the PDF",
                checked = passwordEnabled,
                onCheckedChange = { passwordEnabled = it },
            )
            if (passwordEnabled) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                )
            }

            Button(
                onClick = {
                    onExport(
                        ExportSettings(
                            ocrEnabled = ocrEnabled,
                            compressToTargetSize = compressEnabled,
                            targetSizeMb = targetSizeMb,
                            passwordEnabled = passwordEnabled,
                            password = password,
                        )
                    )
                },
                // Disabled mid-export so a second tap can't queue a duplicate export.
                enabled = !isExporting && (!passwordEnabled || password.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                Text(if (isExporting) "Exporting…" else "Export")
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // The whole row toggles, not just the switch - tapping the label is the natural target.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
