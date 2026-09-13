# Small Fixes / Polish Batch — Design

## Context

Sub-project 3 of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition). Covers five smaller, originally-reported items: export
filename not carrying over, the page-count label's abbreviation, the
launcher icon's white border, a missing paper icon on the home screen, and
two build warnings. Lower risk than sub-projects 1-2 (no core pipeline
logic), but two items (the icon and the targetSdk bump) have real substance
and are documented accordingly; the rest are a few sentences each per the
brainstorming skill's guidance to scale design depth to actual complexity.

Same environment constraints as prior sub-projects: no device/emulator
available, so anything visual/behavioral is verified via a manual checklist
handed to the user. One exception this time: `isShrinkResources` can be
verified directly with a real `./gradlew assembleRelease` in this
environment, since that's a pure build-tool operation, not something that
needs a running device.

## Item 1: Export filename — no code change, verify only

Traced the full export path: `DocumentDetailViewModel.export()` builds the
output filename from `FileUtils.sanitizeFileName(document.title)` (the same
title shown/edited in the app), and the share flow
(`Intent.ACTION_SEND`/`FileProvider`) points the receiving app at that real
file, whose actual on-disk name — which `FileProvider`'s default `query()`
reports as the shared `DISPLAY_NAME` — already reflects the in-app title. No
code path was found that would produce a generic name. The user could not
recall specifics (which app they used to save, or whether this was an older
build) when asked. No fix is planned in this batch; it's called out in the
final on-device checklist for the user to re-test and report back with
specifics if it still happens.

## Item 2: Page count label

`DocumentGridItem.kt` renders `"${document.pageCount}p"`. Change to
`"1 Page"` for a single page, `"N Pages"` otherwise.

## Item 3: Launcher icon white border

Root cause (confirmed via research on Android's adaptive icon system): the
app has one flat vector drawable (`res/drawable/ic_launcher.xml`, a 108x108
canvas with a blue background, a white "paper" shape, and blue text-line
marks) referenced directly as `android:icon`. There is no Android Adaptive
Icon (foreground/background layers + `<adaptive-icon>` XML). On Android 8.0+,
a non-adaptive icon gets auto-wrapped by the OS: shrunk to roughly 75% and
padded with a white circle/squircle background — this is the reported
"white outer border," not something drawn by the icon itself.

**Fix:**
- `res/drawable/ic_launcher_background.xml` — the existing blue full-bleed
  108x108 fill, split into its own layer (background layers are expected to
  bleed to the edge; the OS applies the mask/crop).
- `res/drawable/ic_launcher_foreground.xml` — the paper shape and text-line
  marks only (no background fill), with the paper's dimensions scaled up
  somewhat within the ~72x72 "safe zone" of the 108x108 canvas for the
  additional "zoomed in, easier to see" look requested, while staying inside
  the safe zone so it isn't clipped by circular/squircle launcher masks.
- `res/mipmap-anydpi-v26/ic_launcher.xml` — an `<adaptive-icon>` referencing
  the two layers above, used on API 26+ (this app's actual adaptive-icon
  path; the "white border" bug only manifests on 26+, which is effectively
  all real-world devices this app's `minSdk = 24` might still see).
- `res/mipmap/ic_launcher.xml` — a plain (non-adaptive) vector fallback for
  API 24-25 devices without adaptive icon support, pointing at the
  original, unmodified `@drawable/ic_launcher` (same appearance those
  devices already saw — not a regression, just preserved for a vanishingly
  small population).
- `AndroidManifest.xml`: `android:icon` changes from `@drawable/ic_launcher`
  to `@mipmap/ic_launcher`; `android:roundIcon` is added pointing at the
  same adaptive resource (adaptive icons handle their own masking, so both
  can reference the same resource).
- The original `res/drawable/ic_launcher.xml` is left untouched (still used
  by the new `mipmap/ic_launcher.xml` fallback, and its background/paper
  paths are the source split into the two new layers above).

All of this is vector-XML editing — no raster image generation or external
image-editing tooling is needed.

## Item 4: Home screen paper icon

`HomeScreen.kt`'s `TopAppBar` currently renders `title = { Text("Papr") }`.
Change to a `Row` with a small icon before the text. Rather than a generic
Material icon, reuse the same paper shape being extracted for the launcher
icon in Item 3 (as a small standalone drawable), so the home screen stays
visually consistent with the app's actual logo instead of introducing an
unrelated icon style.

## Item 5: Build warnings

- **`targetSdk` 35 → 37.** `compileSdk` is already 37 (Android 17, stable
  since June 2026). Research found Google Play's minimum-targetSdk
  requirement of **36** had a compliance deadline of August 31, 2026 —
  already passed as of this session — so this app is currently past that
  bar if it's meant for Play Store distribution. Going straight to 37
  (matching `compileSdk`) clears the reported warning and that deadline in
  one move, and gets ahead of Play's next deadline (37, required from
  August 2027) instead of needing a second bump later.
  Risk check: Android 16+ (API 36+) makes edge-to-edge enforcement
  mandatory with no opt-out. This app already calls `enableEdgeToEdge()`
  voluntarily in `MainActivity.onCreate`, and both `HomeScreen` and
  `DocumentDetailScreen` already consume `Scaffold`'s `innerPadding` (which
  includes system-bar insets) correctly. API 37 additionally enforces
  adaptive layouts on large screens (no orientation/resize opt-out) and adds
  new permission gates for local network access and a contact picker — none
  of which this app uses or opts out of today, so no additional code changes
  are expected from the bump itself. This is verified by a full app build
  succeeding (`compileDebugKotlin`/`assembleDebug`/`lintDebug`) plus a
  manual on-device check that the app still looks and behaves correctly
  under edge-to-edge, added to the final checklist.
- **`isShrinkResources = true`.** Added to the `release` build type alongside
  the existing `isMinifyEnabled = true`, which is what the build warning
  asks for directly. Risk check: no `Resources.getIdentifier()` (dynamic
  resource lookup) calls exist anywhere in the codebase, and the app's
  resource footprint is minimal (one drawable, two `values` files, one `xml`
  file) — low risk of shrinking removing something still in use. Verified
  directly with `./gradlew assembleRelease` in this environment (a pure
  build-tool operation, no device needed) — this is the one item in this
  batch that gets a real, non-manual verification.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:lintDebug`, `:app:testDebugUnitTest` all pass.
- `./gradlew :app:assembleRelease` succeeds with `isShrinkResources = true`
  (real, automated verification — not deferred to the manual checklist).
- Page count label change is code-verified (simple string logic, may get a
  small test if `DocumentGridItem`'s pattern supports one — see testing
  approach below).
- Icon and home-screen changes are code-verified for correctness (valid
  vector XML, correct adaptive-icon structure, correct manifest reference)
  and handed off in the manual on-device checklist for visual confirmation,
  since rendering can't be checked without a device.
- targetSdk bump is verified by a full build succeeding, plus the manual
  checklist confirming the app still renders/behaves correctly under
  (now-mandatory) edge-to-edge.
- Export filename gets no code change; the manual checklist asks the user to
  re-test and report back with specifics if it's still wrong.

## Testing approach

Same constraint as prior sub-projects: no device/emulator, no Robolectric —
only plain JUnit tests of pure logic are meaningful here, and this batch has
very little pure logic to test (page-count label formatting is the only
candidate; a small test can be added if it doesn't feel like overkill for a
one-line ternary). Everything visual (icon appearance, home screen icon
placement) and behavioral (edge-to-edge rendering) is verified by full
builds succeeding plus the manual checklist.

## Out of scope

The "confidential" marker stub, file merge, OCR rework, and search — all
separate, already-identified sub-projects.
