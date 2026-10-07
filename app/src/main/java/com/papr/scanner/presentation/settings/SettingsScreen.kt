package com.papr.scanner.presentation.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import com.papr.scanner.BuildConfig
import com.papr.scanner.core.Constants
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.domain.model.ThemeMode
import com.papr.scanner.presentation.common.Haptic
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.SignatureOverlayEditor
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.paywall.ProUpgradeSheet
import com.papr.scanner.usage.FREE_MONTHLY_LIMIT

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onShowTour: () -> Unit) {
    val viewModel = paprViewModel { container ->
        SettingsViewModel(
            container.userPreferencesRepository,
            container.onboardingRepository,
            container.savedSignatureStore,
            container.billingManager,
            container.usageTracker.isPro,
            container.appSettings.value,
        )
    }
    val settings by viewModel.settings.collectAsState()
    val savedSignature by viewModel.savedSignature.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalPaprHaptics.current

    // Saveable, so rotating the phone keeps an open pad, dialog or sheet open.
    var showSignaturePad by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveSignature by rememberSaveable { mutableStateOf(false) }
    var showProSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeading("Appearance")
            SettingLabel("Theme")
            ChoiceRow(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                label = { mode ->
                    when (mode) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                },
                onSelect = { viewModel.setThemeMode(it) },
            )
            // Wallpaper colours only exist from Android 12.
            val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SwitchRow(
                title = "Material You colours",
                subtitle = if (dynamicColorAvailable) {
                    "Match the app's colours to your wallpaper"
                } else {
                    "Needs Android 12 or later"
                },
                checked = settings.dynamicColor && dynamicColorAvailable,
                enabled = dynamicColorAvailable,
                onCheckedChange = { viewModel.setDynamicColor(it) },
            )
            SwitchRow(
                title = "Haptics",
                subtitle = "Vibrate gently to confirm taps, drags and finished tasks",
                checked = settings.hapticsEnabled,
                onCheckedChange = { on ->
                    // Haptics are still off at this moment, so the switch plays its own "on" directly.
                    if (on) ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.TOGGLE_ON)
                    viewModel.setHapticsEnabled(on)
                },
            )

            SectionDivider()
            SectionHeading("Signature")
            val signature = savedSignature
            if (signature != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // Always white, like paper, so the ink reads the same in either theme.
                        .background(Color.White)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PageImage(
                        file = signature.file,
                        contentDescription = "Your saved signature",
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    "Used by \"Use saved\" when you sign a page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Wraps onto two lines at large text sizes rather than squeezing "Remove" letter by letter.
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { showSignaturePad = true }) { Text("Draw new signature") }
                    OutlinedButton(onClick = { confirmRemoveSignature = true }) { Text("Remove") }
                }
            } else {
                Text(
                    "No signature saved. Save one here to add it to any page with one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Button(
                    onClick = { showSignaturePad = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Add a signature") }
            }

            SectionDivider()
            SectionHeading("Documents")
            SwitchRow(
                title = "Delete originals after merging",
                subtitle = "The documents you merge are removed once the merged copy is saved. You can still change this on each merge.",
                checked = settings.deleteOriginalsAfterMerge,
                onCheckedChange = { viewModel.setDeleteOriginalsAfterMerge(it) },
            )
            SettingLabel("Export starts with")
            ChoiceRow(
                options = ExportFormat.entries,
                selected = settings.defaultExportFormat,
                label = { if (it == ExportFormat.PDF) "PDF" else "Images" },
                onSelect = { viewModel.setDefaultExportFormat(it) },
            )

            SectionDivider()
            SectionHeading("Papr Pro")
            // Shown once known: a Pro owner shouldn't see "Free plan" for a frame first.
            isPro?.let { isPro ->
                ListItem(
                    headlineContent = { Text(if (isPro) "You own Papr Pro" else "Free plan") },
                    supportingContent = {
                        Text(
                            if (isPro) {
                                "Every feature, with no monthly limits."
                            } else {
                                "$FREE_MONTHLY_LIMIT a month each of searchable-text, password and compressed exports and merges."
                            }
                        )
                    },
                    trailingContent = if (isPro) null else {
                        { Button(onClick = { showProSheet = true }) { Text("Upgrade") } }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            OutlinedButton(
                onClick = viewModel::restorePurchase,
                enabled = !isRestoring,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Checking Google Play…", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text("Restore purchase")
                }
            }

            SectionDivider()
            SectionHeading("Contact us")
            ListItem(
                headlineContent = { Text(Constants.SUPPORT_EMAIL) },
                supportingContent = { Text("Questions, problems or ideas? Tap to email us.") },
                leadingContent = { Icon(Icons.Outlined.Email, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClickLabel = "Email us") { emailSupport(context) },
            )

            SectionDivider()
            SectionHeading("About")
            ListItem(
                headlineContent = { Text("Papr ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Your documents stay on this phone. Papr never uploads them.") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Show the tour again") },
                supportingContent = { Text("Replay the welcome slides and bring back the tips.") },
                leadingContent = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    viewModel.resetTour()
                    onShowTour()
                },
            )
        }
    }

    if (showSignaturePad) {
        SignatureOverlayEditor(
            onSave = { bitmap, _ ->
                haptics.perform(Haptic.CONFIRM)
                viewModel.saveSignature(bitmap)
                showSignaturePad = false
            },
            onDismiss = { showSignaturePad = false },
            // Saving is the whole point here.
            showRememberOption = false,
        )
    }

    if (confirmRemoveSignature) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSignature = false },
            title = { Text("Remove your saved signature?") },
            text = { Text("Pages you've already signed keep their signatures. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(Haptic.CONFIRM)
                    viewModel.removeSignature()
                    confirmRemoveSignature = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSignature = false }) { Text("Cancel") }
            },
        )
    }

    if (showProSheet) {
        ProUpgradeSheet(onDismiss = { showProSheet = false })
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
}

/** The whole row toggles, not just the switch - the label is the natural thing to tap. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val haptics = LocalPaprHaptics.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { on ->
            haptics.toggle(on)
            onCheckedChange(on)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}

/**
 * Opens the email app addressed to support, with the app version in the subject. Without an
 * email app, copies the address instead, so it can still be pasted somewhere.
 */
private fun emailSupport(context: Context) {
    val subject = Uri.encode("Papr ${BuildConfig.VERSION_NAME}")
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:${Constants.SUPPORT_EMAIL}?subject=$subject".toUri())
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Papr support email", Constants.SUPPORT_EMAIL))
        Toast.makeText(context, "No email app found, so the address was copied.", Toast.LENGTH_LONG).show()
    }
}
