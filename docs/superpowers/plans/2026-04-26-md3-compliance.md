# MD3 Compliance Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Focal's UI from 62/100 to full Material Design 3 compliance — seed-generated color scheme, complete typography, M3 shapes, theme token migration across all screens, accessibility fixes, motion transitions, and adaptive layout.

**Architecture:** Three-tier approach: (1) rewrite theme foundation files with M3-generated palette from #C8956C seed, (2) migrate all 12 screen/component files from hardcoded values to MaterialTheme tokens, (3) add motion transitions and adaptive NavigationRail. Each tier builds on the previous.

**Tech Stack:** Jetpack Compose Material3, MaterialTheme (colorScheme/typography/shapes), Compose Animation (AnimatedContent, animateContentSize, AnimatedVisibility), material3-window-size-class for adaptive layout.

---

### Task 1: Rewrite Color.kt with M3 Seed-Generated Palette

**Files:**
- Rewrite: `app/src/main/java/com/focal/ui/theme/Color.kt`

- [ ] **Step 1: Replace Color.kt entirely**

Replace the entire file with a complete M3 color palette generated from seed #C8956C:

```kotlin
package com.focal.ui.theme

import androidx.compose.ui.graphics.Color

// M3 palette generated from seed #C8956C (Focal warm gold)

// Light scheme
val md_theme_light_primary = Color(0xFF8B5E3C)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFFFDDB8)
val md_theme_light_onPrimaryContainer = Color(0xFF311A00)
val md_theme_light_secondary = Color(0xFF6E5D4B)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFF9DFC8)
val md_theme_light_onSecondaryContainer = Color(0xFF271A0D)
val md_theme_light_tertiary = Color(0xFF5E6237)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFE2E7B1)
val md_theme_light_onTertiaryContainer = Color(0xFF1B1E00)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFFF8F4)
val md_theme_light_onBackground = Color(0xFF201A14)
val md_theme_light_surface = Color(0xFFFFF8F4)
val md_theme_light_onSurface = Color(0xFF201A14)
val md_theme_light_surfaceVariant = Color(0xFFF1DFD0)
val md_theme_light_onSurfaceVariant = Color(0xFF504539)
val md_theme_light_outline = Color(0xFF837468)
val md_theme_light_outlineVariant = Color(0xFFD4C4B5)
val md_theme_light_inverseSurface = Color(0xFF362F28)
val md_theme_light_inverseOnSurface = Color(0xFFFBEEE3)
val md_theme_light_inversePrimary = Color(0xFFF5BB84)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFFFF1E7)
val md_theme_light_surfaceContainer = Color(0xFFF5EDE6)
val md_theme_light_surfaceContainerHigh = Color(0xFFEFE8E0)
val md_theme_light_surfaceContainerHighest = Color(0xFFE9E2DA)

// Dark scheme
val md_theme_dark_primary = Color(0xFFF5BB84)
val md_theme_dark_onPrimary = Color(0xFF4A2800)
val md_theme_dark_primaryContainer = Color(0xFF6D4422)
val md_theme_dark_onPrimaryContainer = Color(0xFFFFDDB8)
val md_theme_dark_secondary = Color(0xFFDCC3AD)
val md_theme_dark_onSecondary = Color(0xFF3D2E20)
val md_theme_dark_secondaryContainer = Color(0xFF554535)
val md_theme_dark_onSecondaryContainer = Color(0xFFF9DFC8)
val md_theme_dark_tertiary = Color(0xFFC7CB97)
val md_theme_dark_onTertiary = Color(0xFF30330D)
val md_theme_dark_tertiaryContainer = Color(0xFF464A22)
val md_theme_dark_onTertiaryContainer = Color(0xFFE2E7B1)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF17120D)
val md_theme_dark_onBackground = Color(0xFFEDE0D5)
val md_theme_dark_surface = Color(0xFF17120D)
val md_theme_dark_onSurface = Color(0xFFEDE0D5)
val md_theme_dark_surfaceVariant = Color(0xFF504539)
val md_theme_dark_onSurfaceVariant = Color(0xFFD4C4B5)
val md_theme_dark_outline = Color(0xFF9D8F81)
val md_theme_dark_outlineVariant = Color(0xFF504539)
val md_theme_dark_inverseSurface = Color(0xFFEDE0D5)
val md_theme_dark_inverseOnSurface = Color(0xFF362F28)
val md_theme_dark_inversePrimary = Color(0xFF8B5E3C)
val md_theme_dark_surfaceContainerLowest = Color(0xFF120D08)
val md_theme_dark_surfaceContainerLow = Color(0xFF201A14)
val md_theme_dark_surfaceContainer = Color(0xFF241E18)
val md_theme_dark_surfaceContainerHigh = Color(0xFF2F2922)
val md_theme_dark_surfaceContainerHighest = Color(0xFF3A342D)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/theme/Color.kt
git commit -m "feat(md3): rewrite Color.kt with M3 seed-generated palette from #C8956C"
```

---

### Task 2: Rewrite Type.kt with Complete 15-Style Scale

**Files:**
- Rewrite: `app/src/main/java/com/focal/ui/theme/Type.kt`

- [ ] **Step 1: Replace Type.kt entirely**

```kotlin
package com.focal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val FocalTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/theme/Type.kt
git commit -m "feat(md3): complete 15-style M3 typography scale"
```

---

### Task 3: Create Shape.kt and Update Theme.kt

**Files:**
- Create: `app/src/main/java/com/focal/ui/theme/Shape.kt`
- Rewrite: `app/src/main/java/com/focal/ui/theme/Theme.kt`

- [ ] **Step 1: Create Shape.kt**

```kotlin
package com.focal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FocalShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
```

- [ ] **Step 2: Rewrite Theme.kt with full M3 color scheme + shapes**

```kotlin
package com.focal.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest
)

@Composable
fun FocalTheme(content: @Composable () -> Unit) {
    val themeMode by ThemePreference.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FocalTypography,
        shapes = FocalShapes,
        content = content
    )
}
```

- [ ] **Step 3: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -5`

The build will show errors for files still referencing removed colors (FocalAccent, FocalPrimary, etc). This is expected — those are fixed in Tasks 4-7.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/ui/theme/Shape.kt app/src/main/java/com/focal/ui/theme/Theme.kt
git commit -m "feat(md3): add M3 shapes and rewrite Theme.kt with full color scheme"
```

---

### Task 4: Migrate Digest Package (DigestScreen, TopicCard, TopicDetailScreen, DigestUiCompat, CategorySection)

**Files:**
- Modify: `app/src/main/java/com/focal/ui/digest/DigestScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/digest/TopicCard.kt`
- Modify: `app/src/main/java/com/focal/ui/digest/TopicDetailScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/digest/DigestUiCompat.kt`
- Modify: `app/src/main/java/com/focal/ui/digest/CategorySection.kt`

- [ ] **Step 1: Migrate all 5 files**

Apply these replacements across all 5 files:

**Color replacements:**
- `FocalAccent` → `MaterialTheme.colorScheme.primary` (every instance)
- Remove `import com.focal.ui.theme.FocalAccent` from all files
- In DigestUiCompat.kt: remove the internal `FocalAccent` property definition (lines 28-29) and use `MaterialTheme.colorScheme.primary` instead
- `.copy(alpha = 0.5f)` on `onSurface` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `.copy(alpha = 0.6f)` on `onSurface` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `.copy(alpha = 0.4f)` on `onSurface` → `MaterialTheme.colorScheme.outline`
- `.copy(alpha = 0.1f)` or `.copy(alpha = 0.08f)` on any color → `MaterialTheme.colorScheme.surfaceContainerHigh`
- `.copy(alpha = 0.7f)` on `onSurface` → `MaterialTheme.colorScheme.onSurface` (already high enough contrast)

**Shape replacements:**
- `RoundedCornerShape(14.dp)` → `MaterialTheme.shapes.medium` (TopicCard)
- `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium` (CategorySection)
- `RoundedCornerShape(16.dp)` → `MaterialTheme.shapes.large` (TopicDetailScreen)
- Remove `import androidx.compose.foundation.shape.RoundedCornerShape` when no longer needed
- Add `import androidx.compose.ui.unit.dp` where still needed for padding

**Typography replacements (letterSpacing):**
- Remove all `letterSpacing = 2.sp` EXCEPT in SectionHeader composable (that's Task 7)
- Remove all `letterSpacing = 1.5.sp` — use the style's built-in spacing
- Where `style = MaterialTheme.typography.labelSmall` is already used with extra `letterSpacing`, keep only `style = MaterialTheme.typography.labelSmall`

**Accessibility:**
- TopicCard: wrap app icon Row in `Modifier.semantics { contentDescription = "Source apps" }`
- TopicDetailScreen: add `contentDescription` to app icons

- [ ] **Step 2: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/digest/
git commit -m "feat(md3): migrate digest package to M3 theme tokens"
```

---

### Task 5: Migrate Pulse Package (PulseScreen, PulseCard, PulseDetailScreen, PulseWizard)

**Files:**
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseCard.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseWizard.kt`

- [ ] **Step 1: Migrate all 4 files**

**Color replacements (all files):**
- `FocalAccent` → `MaterialTheme.colorScheme.primary` (every instance)
- Remove `import com.focal.ui.theme.FocalAccent` from all files
- `FocalAccent.copy(alpha = 0.08f)` → `MaterialTheme.colorScheme.primaryContainer` (PulseWizard, 4 instances)
- `BorderStroke(1.dp, FocalAccent)` → `BorderStroke(1.dp, MaterialTheme.colorScheme.primary)` (PulseWizard)
- `.copy(alpha = 0.5f)` on `onSurface` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `.copy(alpha = 0.6f)` on `onSurface` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `.copy(alpha = 0.4f)` on `onSurface` → `MaterialTheme.colorScheme.outline`
- `.copy(alpha = 0.3f)` on `onSurface` → `MaterialTheme.colorScheme.outline`
- `.copy(alpha = 0.2f)` on `outline` → `MaterialTheme.colorScheme.outlineVariant`
- `surfaceVariant.copy(alpha = 0.5f)` → `MaterialTheme.colorScheme.surfaceContainerLow`

**Shape replacements:**
- `RoundedCornerShape(14.dp)` → `MaterialTheme.shapes.medium` (PulseCard)
- `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium` (PulseCard badge, PulseWizard templates, PulseDetailScreen)
- `RoundedCornerShape(10.dp)` → `MaterialTheme.shapes.small` (PulseWizard operation cards, PulseDetailScreen breakdown)
- `RoundedCornerShape(24.dp)` → `MaterialTheme.shapes.extraLarge` (PulseScreen button)

**Typography:**
- Remove all `letterSpacing = 2.sp` — labelSmall has built-in spacing; category labels like "FINANCE" keep using labelSmall style (0.5sp is sufficient for uppercase)
- PulseCard: `letterSpacing = 2.sp` on category → remove, keep just `style = MaterialTheme.typography.labelSmall`
- PulseScreen: `letterSpacing = 2.sp` on "LIVE" → remove
- PulseDetailScreen: `letterSpacing = 2.sp` on category → remove
- PulseWizard: `letterSpacing = 2.sp` on step label and "OPERATION" → remove

**Accessibility:**
- PulseCard: add `Modifier.semantics { contentDescription = "Recently updated" }` to live indicator dot
- PulseCard: wrap app icons in semantics block
- PulseWizard: add `Modifier.semantics { role = Role.RadioButton }` to template selection cards

- [ ] **Step 2: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/
git commit -m "feat(md3): migrate pulse package to M3 theme tokens"
```

---

### Task 6: Migrate Tune, Setup, and Settings Screens

**Files:**
- Modify: `app/src/main/java/com/focal/ui/tune/TuneScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/setup/SetupScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Migrate all 3 files**

**TuneScreen — color replacements:**
- `FocalAccent` → `MaterialTheme.colorScheme.primary`
- Remove `import com.focal.ui.theme.FocalAccent`
- All `.copy(alpha = X)` → appropriate M3 roles (same mapping as Tasks 4-5)

**TuneScreen — emoji → Material Icons:**
- Replace emoji 🔐 with `Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp))`
- Replace emoji ✨ with `Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))`
- Replace emoji 🔇 with `Icon(Icons.Outlined.VolumeOff, contentDescription = null, modifier = Modifier.size(20.dp))`
- Add import: `import androidx.compose.material.icons.outlined.Lock`, `AutoAwesome`, `VolumeOff`

**TuneScreen — accessibility:**
- MaN pills: add `Modifier.minimumInteractiveComponentSize()` to ensure 48dp touch target
- MaN pills: add `contentDescription = "Set ${app.appName} to $category"` on each pill
- Add import: `import androidx.compose.material3.minimumInteractiveComponentSize`

**Shape replacements (all 3 files):**
- `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium`
- `RoundedCornerShape(8.dp)` → `MaterialTheme.shapes.small`
- `RoundedCornerShape(20.dp)` → `MaterialTheme.shapes.large`

**SetupScreen and SettingsScreen:**
- `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium`
- Any remaining `.copy(alpha)` → appropriate M3 roles

- [ ] **Step 2: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/tune/ app/src/main/java/com/focal/ui/setup/ app/src/main/java/com/focal/ui/settings/
git commit -m "feat(md3): migrate tune, setup, settings to M3 tokens with accessibility fixes"
```

---

### Task 7: Migrate Components (SectionHeader, AppIcon)

**Files:**
- Modify: `app/src/main/java/com/focal/ui/components/SectionHeader.kt`
- Modify: `app/src/main/java/com/focal/ui/components/AppIcon.kt`

- [ ] **Step 1: Update SectionHeader.kt**

SectionHeader keeps `letterSpacing = 2.sp` as intentional brand choice for uppercase headers. Only change: use M3 color token.

Replace any `.copy(alpha)` color with `MaterialTheme.colorScheme.onSurfaceVariant`. Keep the 2.sp letterSpacing and 8.dp padding.

- [ ] **Step 2: Update AppIcon.kt**

- `.copy(alpha = 0.1f)` on background → `MaterialTheme.colorScheme.surfaceContainerHigh`
- `.copy(alpha = 0.6f)` on text → `MaterialTheme.colorScheme.onSurfaceVariant`
- Magic `(size.value * 0.38f).sp` → keep as-is (it's a proportional calculation based on icon size, not a typography token)

- [ ] **Step 3: Verify build compiles cleanly**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (all FocalAccent references should now be gone)

- [ ] **Step 4: Verify no remaining FocalAccent references**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && grep -rn "FocalAccent\|FocalPrimary\|FocalOnPrimary\|DarkBackground\|DarkSurface\|DarkOnBackground\|DarkOnSurface\|LightBackground\|LightSurface\|LightOnBackground\|LightOnSurface\|DigestBlue\|NoiseSurface" --include="*.kt" app/src/main/java/`
Expected: No matches (all old color names removed)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/ui/components/
git commit -m "feat(md3): migrate SectionHeader and AppIcon to M3 tokens"
```

---

### Task 8: Motion — Screen Transitions in FocalNavigation

**Files:**
- Modify: `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`

- [ ] **Step 1: Add M3 transition specs to NavHost**

Add imports at top of file:
```kotlin
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
```

Add default transitions to the NavHost:
```kotlin
NavHost(
    navController = navController,
    startDestination = Screen.Digest.route,
    modifier = Modifier.padding(innerPadding),
    enterTransition = { fadeIn(animationSpec = tween(300)) },
    exitTransition = { fadeOut(animationSpec = tween(300)) },
    popEnterTransition = { fadeIn(animationSpec = tween(300)) },
    popExitTransition = { fadeOut(animationSpec = tween(300)) }
)
```

For parent-to-child routes (TopicDetail, PulseDetail), override with shared-axis-Z:
```kotlin
composable(
    route = Screen.TopicDetail.route,
    arguments = listOf(navArgument("topicId") { type = NavType.StringType }),
    enterTransition = {
        scaleIn(initialScale = 0.85f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
    },
    exitTransition = {
        scaleOut(targetScale = 1.1f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
    },
    popEnterTransition = {
        scaleIn(initialScale = 1.1f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
    },
    popExitTransition = {
        scaleOut(targetScale = 0.85f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
    }
)
```

Apply same pattern to PulseDetail composable.

For Setup screen (forward nav from Tune), use slide:
```kotlin
composable(
    Screen.Setup.route,
    enterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
    },
    popExitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
    }
)
```

- [ ] **Step 2: Verify build and test transitions**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -5`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt
git commit -m "feat(md3): add M3 screen transitions (fade-through, shared-axis-Z, slide)"
```

---

### Task 9: Motion — Component Animations

**Files:**
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseCard.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseWizard.kt`

- [ ] **Step 1: PulseCard — animateContentSize + LIVE pulse**

In PulseCard.kt, add to the Column modifier:
```kotlin
Column(modifier = Modifier.padding(14.dp).animateContentSize())
```

Add a pulsing animation for the live dot:
```kotlin
val infiniteTransition = rememberInfiniteTransition(label = "live")
val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.4f,
    animationSpec = infiniteRepeatable(
        animation = tween(1000),
        repeatMode = RepeatMode.Reverse
    ),
    label = "pulse"
)
```

Apply `pulseAlpha` to the live indicator dot:
```kotlin
Text(text = "●", color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha), ...)
```

Add imports: `import androidx.compose.animation.animateContentSize`, `import androidx.compose.animation.core.*`

- [ ] **Step 2: PulseScreen — LIVE text pulse**

Same pulsing animation for the "LIVE" text in PulseScreen. Use the same `rememberInfiniteTransition` pattern.

- [ ] **Step 3: PulseWizard — AnimatedContent for step transitions**

Wrap the `when (step)` block in `AnimatedContent`:
```kotlin
AnimatedContent(
    targetState = step,
    transitionSpec = {
        if (targetState > initialState) {
            slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
        } else {
            slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
        }
    },
    label = "wizard-step"
) { currentStep ->
    when (currentStep) {
        1 -> { /* step 1 content */ }
        2 -> { /* step 2 content */ }
        3 -> { /* step 3 content */ }
    }
}
```

Add imports: `import androidx.compose.animation.*`

- [ ] **Step 4: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -5`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/
git commit -m "feat(md3): add component animations (pulse LIVE, animateContentSize, wizard transitions)"
```

---

### Task 10: Adaptive Layout — Window Size Classes + NavigationRail

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`
- Modify: `app/src/main/java/com/focal/MainActivity.kt`

- [ ] **Step 1: Add window-size-class dependency**

In `app/build.gradle.kts`, add after the material3 dependency:
```kotlin
implementation("androidx.compose.material3:material3-window-size-class")
```

- [ ] **Step 2: Pass WindowSizeClass from MainActivity to FocalNavigation**

In `MainActivity.kt`, update the FocalTheme call to pass window size class:
```kotlin
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass

// In setContent:
val windowSizeClass = calculateWindowSizeClass(this)
FocalTheme {
    FocalNavigation(windowSizeClass = windowSizeClass)
}
```

- [ ] **Step 3: Update FocalNavigation to accept WindowSizeClass and switch nav**

Add parameter: `fun FocalNavigation(windowSizeClass: WindowSizeClass)`.

Add imports:
```kotlin
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
```

Replace the Scaffold's `bottomBar` with conditional navigation:
```kotlin
val useRail = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

Scaffold(
    bottomBar = {
        if (!useRail) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = { /* same nav logic */ }
                    )
                }
            }
        }
    }
) { innerPadding ->
    Row(modifier = Modifier.padding(innerPadding)) {
        if (useRail) {
            NavigationRail {
                bottomNavItems.forEach { screen ->
                    NavigationRailItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = { /* same nav logic */ }
                    )
                }
            }
        }
        NavHost(
            navController = navController,
            startDestination = Screen.Digest.route,
            modifier = Modifier.weight(1f),
            // ... transitions from Task 8
        ) {
            // ... routes unchanged
        }
    }
}
```

- [ ] **Step 4: Verify build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -5`

- [ ] **Step 5: Run all unit tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:testDebugUnitTest 2>&1 | tail -10`

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/focal/MainActivity.kt app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt
git commit -m "feat(md3): add adaptive layout with NavigationRail for medium+ screens"
```

---

### Task 11: Final Build, Test, and Push

- [ ] **Step 1: Full APK build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:testDebugUnitTest 2>&1 | tail -20`
Expected: All tests pass

- [ ] **Step 3: Verify no remaining old color references**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && grep -rn "FocalAccent\|FocalPrimary\|DarkBackground\|DarkSurface\|LightBackground\|LightSurface\|NoiseSurface\|DigestBlue" --include="*.kt" app/src/main/java/ | grep -v "Color.kt" | grep -v "test/"`
Expected: No matches

- [ ] **Step 4: Push to remote**

```bash
git push origin dev-darahas
```
