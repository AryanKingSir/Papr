package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.pdf.security.StandardSecurityHandler
import com.papr.scanner.presentation.common.HapticsFollowSetting
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.detail.ExportSettings
import com.papr.scanner.usage.Allowance
import com.papr.scanner.usage.MeteredFeature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    isExporting: Boolean,
    // The Settings default; the sheet can still switch for this export.
    initialFormat: ExportFormat,
    allowances: Map<MeteredFeature, Allowance>,
    onExport: (ExportSettings) -> Unit,
    onLockedOptionTap: (MeteredFeature) -> Unit,
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The Pro sheet opens on top of this one; the password keyboard mustn't stay up behind it.
    val lockedTap = { feature: MeteredFeature ->
        keyboard?.hide()
        focusManager.clearFocus()
        onLockedOptionTap(feature)
    }

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
                    allowance = allowances[MeteredFeature.OCR_EXPORT],
                    onLockedTap = { lockedTap(MeteredFeature.OCR_EXPORT) },
                )
                OptionRow(
                    title = "Compress to target size",
                    subtitle = "Reduce image quality to hit a target file size",
                    checked = compressEnabled,
                    onCheckedChange = { compressEnabled = it },
                    allowance = allowances[MeteredFeature.COMPRESSED_EXPORT],
                    onLockedTap = { lockedTap(MeteredFeature.COMPRESSED_EXPORT) },
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
                    allowance = allowances[MeteredFeature.PASSWORD_EXPORT],
                    onLockedTap = { lockedTap(MeteredFeature.PASSWORD_EXPORT) },
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
    // Null while it's unknown or unlimited (Pro): the row then shows no count.
    allowance: Allowance?,
    onLockedTap: () -> Unit,
) {
    val remaining = allowance?.remaining
    val locked = remaining == 0
    val haptics = LocalPaprHaptics.current
    // The whole row toggles, not just the switch - tapping the label is the natural target.
    // A used-up option can't be switched on; tapping it explains why instead.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (locked) {
                    Modifier.clickable(role = Role.Button, onClick = onLockedTap)
                } else {
                    Modifier.toggleable(value = checked, role = Role.Switch) { on ->
                        haptics.toggle(on)
                        onCheckedChange(on)
                    }
                }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelLarge)
            if (remaining != null) {
                Text(
                    if (locked) "None left this month" else "$remaining of ${allowance.limit} left this month",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (locked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Needs Papr Pro",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
