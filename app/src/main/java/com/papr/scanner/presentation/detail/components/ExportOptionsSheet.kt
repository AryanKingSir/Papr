package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.pdf.security.StandardSecurityHandler
import com.papr.scanner.presentation.common.HapticsFollowSetting
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.detail.ExportSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    isExporting: Boolean,
    // The Settings default; the sheet can still switch for this export.
    initialFormat: ExportFormat,
    onExport: (ExportSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open from the start: half open, turning on a password pushed Export below the screen's edge.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saveable, so options survive a rotation - except the password, which isn't written into
    // saved state (that can end up on disk).
    var format by rememberSaveable { mutableStateOf(initialFormat) }
    var ocrEnabled by rememberSaveable { mutableStateOf(false) }
    var compressEnabled by rememberSaveable { mutableStateOf(false) }
    var targetSizeMb by rememberSaveable { mutableFloatStateOf(1f) }
    var passwordEnabled by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val passwordSupported = StandardSecurityHandler.isSupportedPassword(password)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrolls so Export stays reachable when the options don't fit: landscape,
        // large text, or with the compression and password fields both open.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Export options", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                ExportFormat.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = format == option,
                        onClick = { format = option },
                        shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
                    ) { Text(if (option == ExportFormat.PDF) "PDF" else "Images") }
                }
            }

            if (format == ExportFormat.IMAGES) {
                Text(
                    "Each page as a JPG image, with its markup, signature and confidential marks.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
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
                        // TalkBack would otherwise read the position as a percentage.
                        modifier = Modifier.semantics {
                            contentDescription = "Target size"
                            stateDescription = "${"%.1f".format(targetSizeMb)} MB"
                        },
                    )
                }
                OptionRow(
                    title = "Password protect",
                    subtitle = "Require a password to open the PDF",
                    checked = passwordEnabled,
                    onCheckedChange = { passwordEnabled = it },
                )
                if (passwordEnabled) {
                    HapticsFollowSetting {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            isError = !passwordSupported,
                            supportingText = if (passwordSupported) {
                                null
                            } else {
                                { Text("Use only letters, numbers and symbols from a standard keyboard, so every PDF reader can open it.") }
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onExport(
                        ExportSettings(
                            format = format,
                            ocrEnabled = ocrEnabled,
                            compressToTargetSize = compressEnabled,
                            targetSizeMb = targetSizeMb,
                            passwordEnabled = passwordEnabled,
                            password = password,
                        )
                    )
                },
                // Disabled mid-export so a second tap can't queue a duplicate export.
                enabled = !isExporting &&
                    (format == ExportFormat.IMAGES || !passwordEnabled || (password.isNotBlank() && passwordSupported)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                Text(
                    when {
                        isExporting -> "Exporting…"
                        format == ExportFormat.IMAGES -> "Share images"
                        else -> "Export"
                    }
                )
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
    val haptics = LocalPaprHaptics.current
    // The whole row toggles, not just the switch - tapping the label is the natural target.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch) { on ->
                haptics.toggle(on)
                onCheckedChange(on)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelLarge)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
