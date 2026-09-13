# Small Fixes / Polish Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the page-count label, migrate the launcher icon to a proper Android Adaptive Icon (eliminating the OS-injected white border), add a paper icon before "Papr" on the home screen, and clear both build warnings (targetSdk, isShrinkResources).

**Architecture:** Five independent, low-risk changes across UI text, two new vector-icon resource sets, a manifest reference change, and two `build.gradle.kts` flags. No shared code between tasks.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android vector drawables / adaptive icons, Gradle/AGP build config.

## Global Constraints

- No unrelated refactors — every changed line traces to one of this plan's five items (per project CLAUDE.md).
- No new dependencies.
- This project has no Robolectric/instrumented tests — only plain JUnit tests of pure logic exist. Anything visual (icon rendering, home screen layout) or behavioral (edge-to-edge under the new targetSdk) is verified by code reasoning during implementation plus the on-device checklist in the final task.
- **Git hygiene:** `AndroidManifest.xml` currently has one line of pre-existing uncommitted content (`android:largeHeap="true"`, added before this whole multi-session engagement, not yet committed). Task 2 below handles this explicitly: commit that pre-existing line on its own first, *then* make and commit this plan's own manifest edit, so no commit in this plan silently bundles unrelated pre-existing work (this exact mistake happened once already in an earlier sub-project of this engagement and was corrected — don't repeat it). Read each file's current on-disk content before editing — don't assume it matches exactly what's shown below if something else has touched it since this plan was written.
- Item 1 from the spec (export filename) has no task here — it's verify-only, folded into the final task's manual checklist.

---

### Task 1: Page count label

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt`

**Interfaces:** none (self-contained UI text change).

- [ ] **Step 1: Change the page-count text**

Read the file first to confirm current content. Change:
```kotlin
                Text(
                    text = "${document.pageCount}p",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
```
to:
```kotlin
                Text(
                    text = if (document.pageCount == 1) "1 Page" else "${document.pageCount} Pages",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt
git commit -m "Show page count as '1 Page' / 'N Pages' instead of 'Np'"
```

---

### Task 2: Launcher icon — adaptive icon migration

The current `app/src/main/res/drawable/ic_launcher.xml` (108x108 viewport, do not modify it — it's kept as-is and reused below as the pre-API-26 fallback source):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M30,24 h34 l14,14 v46 a4,4 0 0 1 -4,4 h-44 a4,4 0 0 1 -4,-4 v-56 a4,4 0 0 1 4,-4 z" />
    <path
        android:fillColor="#D8E2FF"
        android:pathData="M64,24 l14,14 h-10 a4,4 0 0 1 -4,-4 z" />
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M36,50 h30 v4 h-30 z M36,60 h30 v4 h-30 z M36,70 h20 v4 h-20 z" />
</vector>
```

The foreground layer below scales every coordinate of the paper/fold/text-line paths (NOT the background) by 1.15x around the canvas center (54,54) — `new = 54 + (old - 54) * 1.15` — to fill more of the adaptive-icon canvas, per the spec's "zoom in" request. This was checked against the tightest real icon mask (a full circle of radius 54 centered at 54,54, the most aggressive shape any launcher actually uses): every scaled point's distance from (54,54) is at most ≈47.9, safely inside the radius-54 circle, so nothing gets clipped even though a couple of points sit slightly outside the more conservative "72x72 safe zone" guideline meant for parallax motion, not static clipping.

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap/ic_launcher.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:** none (resource-only + one manifest attribute change).

- [ ] **Step 1: Create the background layer**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M0,0h108v108h-108z" />
</vector>
```
Save as `app/src/main/res/drawable/ic_launcher_background.xml`.

- [ ] **Step 2: Create the foreground layer**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M26.4,19.5 L65.5,19.5 L81.6,35.6 L81.6,88.5 A4.6,4.6 0 0 1 77,93.1 L26.4,93.1 A4.6,4.6 0 0 1 21.8,88.5 L21.8,24.1 A4.6,4.6 0 0 1 26.4,19.5 Z" />
    <path
        android:fillColor="#D8E2FF"
        android:pathData="M65.5,19.5 L81.6,35.6 L70.1,35.6 A4.6,4.6 0 0 1 65.5,31 Z" />
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M33.3,49.4 L67.8,49.4 L67.8,54 L33.3,54 Z M33.3,60.9 L67.8,60.9 L67.8,65.5 L33.3,65.5 Z M33.3,72.4 L56.3,72.4 L56.3,77 L33.3,77 Z" />
</vector>
```
Save as `app/src/main/res/drawable/ic_launcher_foreground.xml`.

- [ ] **Step 3: Create the adaptive-icon XML for API 26+**

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```
Save as `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (create the `mipmap-anydpi-v26` directory).

- [ ] **Step 4: Create the pre-API-26 fallback (exact duplicate of the original icon, unscaled)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M30,24 h34 l14,14 v46 a4,4 0 0 1 -4,4 h-44 a4,4 0 0 1 -4,-4 v-56 a4,4 0 0 1 4,-4 z" />
    <path
        android:fillColor="#D8E2FF"
        android:pathData="M64,24 l14,14 h-10 a4,4 0 0 1 -4,-4 z" />
    <path
        android:fillColor="#2E5AAC"
        android:pathData="M36,50 h30 v4 h-30 z M36,60 h30 v4 h-30 z M36,70 h20 v4 h-20 z" />
</vector>
```
Save as `app/src/main/res/mipmap/ic_launcher.xml` (create the `mipmap` directory — note: no density qualifier, this is the base/default fallback used on any API level that doesn't match `mipmap-anydpi-v26`).

- [ ] **Step 5: Compile so far (resource-only change, should already succeed)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit the new icon resources on their own (before touching the manifest)**

```bash
git add app/src/main/res/drawable/ic_launcher_background.xml app/src/main/res/drawable/ic_launcher_foreground.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap/ic_launcher.xml
git commit -m "Add adaptive icon layers for the launcher icon"
```

- [ ] **Step 7: Handle AndroidManifest.xml's pre-existing uncommitted line**

Run `git diff -- app/src/main/AndroidManifest.xml` and confirm it shows exactly one pre-existing uncommitted line, `android:largeHeap="true"` added to the `<application>` tag (per the Global Constraints note above). If it shows something different or nothing, stop and re-read this plan's Global Constraints section before proceeding — the instructions below assume this specific starting state.

If it matches, commit that pre-existing line on its own first, *before* this task's own edit:
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "Checkpoint pre-existing largeHeap manifest change"
```

- [ ] **Step 8: Point the manifest at the new adaptive icon**

Read `app/src/main/AndroidManifest.xml` now (it should be clean — no uncommitted changes, since Step 7 just committed the only pre-existing one). Change:
```xml
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
```
to:
```xml
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
```

- [ ] **Step 9: Compile**

Run: `./gradlew :app:compileDebugKotlin :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit this task's own manifest edit**

```bash
git diff --stat -- app/src/main/AndroidManifest.xml
```
Expected: `1 file changed, 2 insertions(+), 1 deletion(-)` — only the `android:icon`/`android:roundIcon` lines, confirming Step 7 already isolated the pre-existing `largeHeap` line into its own commit.
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "Point the manifest at the new adaptive launcher icon"
```

---

### Task 3: Home screen paper icon

Uses a separate, tightly-cropped vector (not the full 108x108 launcher canvas) so it renders as a normal-sized 24dp toolbar icon without excess padding. Colors in this vector don't matter for the final look — Compose's `Icon()` composable tints its whole painter to a single color by default (`LocalContentColor.current`, via `SrcIn` blending, which replaces every path's color uniformly regardless of what's set in the XML), so this automatically matches the title text's color and adapts correctly to light/dark theme without any extra work. Do **not** pass `tint = Color.Unspecified` to `Icon` here — that would be needed only to preserve multiple distinct colors, which isn't the goal for a toolbar icon.

**Files:**
- Create: `app/src/main/res/drawable/ic_paper.xml`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt`

**Interfaces:** none.

- [ ] **Step 1: Create the paper icon**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="56"
    android:viewportHeight="68">
    <path
        android:fillColor="#000000"
        android:pathData="M6,2 L40,2 L54,16 L54,62 A4,4 0 0 1 50,66 L6,66 A4,4 0 0 1 2,62 L2,6 A4,4 0 0 1 6,2 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M40,2 L54,16 L44,16 A4,4 0 0 1 40,12 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M12,28 L42,28 L42,32 L12,32 Z M12,38 L42,38 L42,42 L12,42 Z M12,48 L32,48 L32,52 L12,52 Z" />
</vector>
```
Save as `app/src/main/res/drawable/ic_paper.xml`.

- [ ] **Step 2: Add the icon before the "Papr" title**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt` first to confirm its current content. Add these imports (alongside the existing `androidx.compose.foundation.layout.*` and `androidx.compose.material3.*`/`androidx.compose.ui.*` imports):
```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.papr.scanner.R
```

Change:
```kotlin
            TopAppBar(
                title = { Text("Papr") },
```
to:
```kotlin
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_paper),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Papr")
                    }
                },
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_paper.xml app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt
git commit -m "Add a small paper icon before 'Papr' on the home screen"
```

---

### Task 4: Fix the two build warnings

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:** none.

- [ ] **Step 1: Bump targetSdk**

Read `app/build.gradle.kts` first to confirm its current content. Change:
```kotlin
        minSdk = 24
        targetSdk = 35
```
to:
```kotlin
        minSdk = 24
        targetSdk = 37
```

- [ ] **Step 2: Enable resource shrinking alongside minification**

Change:
```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```
to:
```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```

- [ ] **Step 3: Verify with a full debug + release build**

Run: `./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease`
Expected: `BUILD SUCCESSFUL` for all three — `assembleRelease` succeeding is the real, automated check that `isShrinkResources = true` doesn't break anything (a pure build-tool operation, no device needed).

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "$(cat <<'EOF'
Target SDK 37 and enable resource shrinking

targetSdk was 35 against a compileSdk of 37 (already stable since June
2026); Google Play's targetSdk-36 compliance deadline (Aug 31, 2026)
has already passed, and going straight to 37 matches compileSdk and
gets ahead of Play's next deadline (37, required from Aug 2027) instead
of needing a second bump later. isShrinkResources was missing despite
isMinifyEnabled being on, which is exactly what the build warning
flagged - the codebase has no Resources.getIdentifier() dynamic lookups
and a tiny resource footprint, so the risk of shrinking removing
something still in use is low.
EOF
)"
```

---

### Task 5: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Gradle check**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` for all five, 0 lint errors, all unit tests pass.

- [ ] **Step 2: Manual on-device checklist**

No device/emulator is available in this environment — install and hand this checklist to the user:

```bash
./gradlew :app:installDebug
```
1. Open the home screen — confirm the document grid shows "1 Page" for single-page documents and "N Pages" (e.g. "4 Pages") for multi-page ones, not "1p"/"4p".
2. Check the app's icon in the launcher/app drawer and in the recents/overview screen — confirm there's no visible white border/ring around it, and that the blue-and-paper logo looks appropriately larger/more zoomed-in than before.
3. Open the home screen — confirm a small paper icon appears immediately before the "Papr" title in the top bar, and that it's visible and correctly colored in both light and dark theme (toggle the device's dark mode to check both).
4. General smoke-test after the targetSdk bump to 37: scroll the home screen, open a document, confirm nothing looks clipped or misaligned around the status bar / navigation bar (edge-to-edge is now mandatory at this targetSdk) — since the app already called `enableEdgeToEdge()` and both screens already consume `Scaffold`'s inset padding, this is expected to look identical to before, but confirm it.
5. Re-test the export filename issue from item 1 of the spec: export a document you've renamed in-app (e.g. to "Bio"), save the shared PDF somewhere (Files app, Drive, etc.), and check what filename it actually gets. Report back whether it's still wrong, and if so, which app you used to save it and what the exact resulting filename was — that's the information needed to actually find this bug, which wasn't found by reading the code.

Report what you observe for each.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't. Anything unexpected (especially on item 5, the export filename re-test) is a new finding for a future fix, not something to guess at blindly.
