# Settings page, saved-signature rules, delete originals after merging, logo, selection check — design (2026-10-04)

## Context

The editor redesign and markup are done (main = e4b4c0d). The user now wants:
- A **Settings page** where app settings can be adjusted.
- Managing the saved signature from **Settings**, not the editor.
- **"Delete originals after merging"** as a setting.
- An **in-app logo** that matches the real app icon. Today `ic_paper` is a one-colour silhouette whose lines are the same colour as the page, so it shows as a blank sheet.
- A **cleaner selection check**: today it's a Material checkbox inside a dark translucent circle, which the user finds ugly.

Decisions the user made (2026-10-04):
- **Settings entry:** a gear icon at the right of Home's top bar, after the Pro button.
- **Settings contents:**
  - Appearance: theme (System / Light / Dark) and a Material You switch.
  - Saved signature.
  - Documents: delete originals after merging, and the default export format (PDF / Images).
  - Papr Pro: status, Upgrade, Restore purchase.
  - About.
- **Signature flow:**
  - Editor → Sign with **nothing saved**: "Draw new", whose dialog keeps "Remember this signature".
  - Editor → Sign with **a signature saved**: "Use saved", "Draw new" (a one-off for this page, with **no** remember box, so it can't replace the saved one), and "Remove from page". "Forget saved" leaves the editor.
  - Settings: preview of the saved signature, plus "Draw new signature" (replaces it) and "Remove". With none saved: "Add a signature".
- **Delete originals:** Settings holds the default (off). The merge screen has a "Delete the originals" switch that starts at that default. When it's on, the button reads **"Merge and delete originals"**, with no extra dialog. Originals are deleted only after the merge succeeds.
- **Logo:** a mini app icon. A rounded Papr-blue tile with the white page, light-blue folded corner and blue lines, exactly like the launcher icon. It stays brand blue even with Material You on.
- **Selection check:** a squircle on its own.
  - Unselected: a white border with a faint dark tint inside.
  - Selected: solid theme colour with a white check.
  - Used on Home and in Organize pages.

Settled earlier and still true: drag-to-place signature, signatures saved per page, confidential marks baked in, free limits unchanged.

## Design

### Settings storage
- `data/local/datastore/UserPreferencesRepository.kt` gains an `AppSettings` flow plus setters. New keys, with defaults that match today's behaviour:
  - `theme_mode` (SYSTEM | LIGHT | DARK, default SYSTEM)
  - `dynamic_color` (default true)
  - `delete_originals_after_merge` (default false)
  - `default_export_format` (PDF | IMAGES, default PDF)
- Enums are stored by name. An unknown value falls back to the default.

### Theme
- `MainActivity` collects the settings and calls `PaprTheme(darkTheme, dynamicColor)`. `PaprTheme` already takes both, so nothing changes in `presentation/theme/Theme.kt`.
- To avoid flashing the wrong theme, the activity shows nothing until the first settings value arrives (a few ms).
- The status bar must follow the chosen theme, not the phone's. `enableEdgeToEdge(SystemBarStyle...)` gets light or dark styles derived from the chosen mode.
- **Material You switch:** wallpaper colours only exist on Android 12+. On older phones the switch is disabled and says "Needs Android 12 or later".

### Saved signature: `SavedSignatureStore`
- A new class (in `core/` or `data/`, a singleton in `AppContainer`) that owns `filesDir/signature/saved_signature.png`. It replaces the saved-signature code now inside `DocumentDetailViewModel` (`useSavedSignature`, `forgetSavedSignature`, the `init` file check, and the copy in `setSignature`).
  - `state: StateFlow<SavedSignature?>` (file + lastModified). The editor and Settings both observe it, so a change made in Settings shows in the editor right away.
  - `save(bitmap)`, `delete()`, `copyInto(dir): File`.
  - `FileUtils.savedSignatureFile` moves into the store.
- **Editor** (`DocumentDetailScreen` Sign tools):
  - "Use saved" appears when a signature is saved.
  - "Draw new" is always there.
  - "Remove" is renamed **"Remove from page"**.
  - "Forget saved" is deleted.
  - `SignatureOverlayEditor` gets `showRememberOption`, which is false when a signature is saved. The ViewModel also refuses to overwrite a saved signature from the editor.
  - The Sign hint wording updates.
- `SignatureOverlayEditor` moves to `presentation/common/` because Settings reuses it.

### Settings screen
- New route `Screen.Settings`. New `presentation/settings/SettingsScreen.kt` and `SettingsViewModel.kt`, with dependencies on `UserPreferencesRepository`, `SavedSignatureStore`, `BillingManager` and `UsageTracker.isPro`.
- Layout: a TopAppBar "Settings" with Back, then a scrolling column of sections. Each section has a heading and rows built from Material 3 `ListItem`s.
  - **Appearance:**
    - Theme as a `SingleChoiceSegmentedButtonRow` (System / Light / Dark).
    - "Material You colours" switch, subtitled "Match the app's colours to your wallpaper".
  - **Signature:**
    - A white rounded preview of the saved signature (`PageImage`, which reloads by lastModified).
    - Buttons "Draw new signature" (opens `SignatureOverlayEditor`) and "Remove", the latter with a confirm dialog, since removal can't be undone.
    - When nothing is saved: "No signature saved" and "Add a signature".
  - **Documents:**
    - "Delete originals after merging" switch, subtitled "The documents you merge are removed once the merged copy is saved".
    - "Export starts with" as a PDF / Images segmented row.
  - **Papr Pro:**
    - "You own Papr Pro", or "Free plan" with an Upgrade button that opens the existing `ProUpgradeSheet`.
    - **Restore purchase** calls `BillingManager.connect()`, which already re-checks owned purchases. It then shows a toast: "Papr Pro restored", "No Pro purchase found on this Google account", or "Couldn't reach Google Play. Try again".
  - **About:** "Papr" with `BuildConfig.VERSION_NAME`, and "Your documents stay on this phone. Papr never uploads them."
- **Home:** a gear `IconButton` ("Settings") goes after the Pro pill. `PaprNavHost` navigates to Settings.

### Delete originals after merging
- `MergeConfirmViewModel` gains `deleteOriginals: Boolean` in `MergeConfirmUiState`. It starts from the setting and survives process death through `SavedStateHandle` (the screen already keeps the order and title this way). Plus a `setDeleteOriginals()` setter.
- `merge()` order: `documentMerger.merge` succeeds, `usageTracker.recordUse`, then, if the switch is on, each original is deleted via the existing `DocumentDeleter`. A failed delete is logged but doesn't undo the merge.
- `MergeConfirmScreen`: a switch row "Delete the originals". The button reads "Merge and delete originals" when it's on.
- `DocumentMerger` itself is unchanged.

### Default export format
- `ExportOptionsSheet` gets `initialFormat`. `DocumentDetailViewModel` exposes the setting.

### Logo
- New `res/drawable/ic_logo.xml`: a vector with a rounded-square `#2E5AAC` tile and the launcher foreground's three paths (page, fold, lines) at the same proportions.
- Home's top bar draws it with `Image` instead of `Icon`, so it isn't tinted, at 28dp.
- `ic_paper.xml` is deleted if nothing else uses it (only `HomeScreen` does today).

### Selection check
- New `presentation/common/SelectionCheck.kt`, a 24dp box with `RoundedCornerShape(7.dp)`:
  - Selected: `primary` fill with a white Check icon.
  - Unselected: `Color.Black.copy(alpha = 0.25f)` fill with a 2dp white (90%) border.
  - Semantics state "Selected" / "Not selected".
- It replaces the Surface(CircleShape) + `Checkbox` in `home/components/DocumentGridItem.kt` and `detail/components/OrganizePagesScreen.kt`. The signature dialog's checkbox stays a normal checkbox.

## Implementation order (one commit each on main; push only when asked)
0. Save this design as `docs/superpowers/specs/2026-10-04-settings-design.md` and commit it. Invoke `frontend-design` before the UI steps.
1. `AppSettings` in `UserPreferencesRepository`, test first: defaults, round-trip, unknown value falls back. Use the `PreferenceDataStoreFactory` temp-file pattern from `usage/UsageTrackerTest.kt`.
2. `SavedSignatureStore` plus the editor signature rules. Move `SignatureOverlayEditor` to common.
3. Settings screen and route, plus the gear on Home. This wires theme, Material You, signature, Pro/restore and About.
4. Delete originals after merging (setting plus merge-screen switch).
5. Default export format.
6. Logo.
7. Selection check.
8. Add a Settings section to `docs/superpowers/manual-testing-checklist.md` and record the decisions in memory.

## Files (main ones)
- `MainActivity.kt`, `di/AppContainer.kt`, `data/local/datastore/UserPreferencesRepository.kt`
- New: `presentation/settings/{SettingsScreen,SettingsViewModel}.kt`, `presentation/common/SelectionCheck.kt`, the `SavedSignatureStore`, `res/drawable/ic_logo.xml`
- `presentation/navigation/{Screen,PaprNavHost}.kt`
- `presentation/home/{HomeScreen.kt, components/DocumentGridItem.kt}`
- `presentation/detail/{DocumentDetailScreen,DocumentDetailViewModel}.kt`, `components/{ExportOptionsSheet,OrganizePagesScreen}.kt`
- `SignatureOverlayEditor.kt` moves to common
- `presentation/merge/{MergeConfirmScreen,MergeConfirmViewModel,MergeConfirmUiState}.kt`
- `core/FileUtils.kt`: `savedSignatureFile` moves out

## Verification
- **Gates:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` with `JAVA_HOME` set to Studio's JBR. All tests pass (145 plus the new ones), 0 warnings, and lint reports no issues (re-run `lintAnalyzeDebug --rerun` if it shows a stale result).
- **Emulator** (Pixel_6_Pro_Android_12), debug and the release build (now signed, installs directly):
  - Theme: System / Light / Dark switch live, the status bar icons follow, and the choice survives a restart.
  - Material You off: Papr blue.
  - Signature, all paths:
    - Editor with nothing saved: draw + remember.
    - Then saved: Use saved, a one-off Draw new with no remember box, Remove from page.
    - Settings: preview, replace (the preview updates), remove; the editor reflects it.
  - Merge with the switch on: the new document opens and the originals are gone from Home along with their files and thumbnails. With the switch off, the originals stay. The setting changes the switch's starting value.
  - Default export format opens the sheet on Images.
  - Restore purchase shows the right message.
  - The logo matches the launcher icon in light and dark.
  - The selection check on Home and in Organize pages looks right on white and coloured pages.
  - Landscape and font_scale 2.0 on Settings.
- **Android 7 AVD:** the Material You switch is disabled with its reason, and Settings renders.
