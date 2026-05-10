# MD3 Compliance Remediation Design

Bring Focal's UI from 62/100 to full Material Design 3 compliance while preserving the warm, personal design identity. Three tiers: foundation (color/typography/shapes), screen migration + accessibility, and polish (motion/adaptive).

## Audit Summary

Current score: 62/100. Key gaps: 13+ hardcoded Color() values, only 4/15 typography styles defined, hardcoded shapes everywhere, 15+ missing contentDescriptions, touch targets below 48dp, zero motion/animation, no adaptive layout.

## Tier 1: Foundation

### Color Scheme

Generate full M3 color scheme using FocalAccent #C8956C as the seed color. The M3 tonal palette algorithm produces harmonious colors that preserve the warm gold identity with proper contrast ratios.

**Light scheme (key roles):**
- primary: #8B5E3C, onPrimary: #FFFFFF
- primaryContainer: #FFDDB8, onPrimaryContainer: #311A00
- secondary: #6E5D4B, onSecondary: #FFFFFF
- secondaryContainer: #F9DFC8, onSecondaryContainer: #271A0D
- tertiary: #5E6237, tertiaryContainer: #E2E7B1
- background/surface: #FFF8F4, onBackground/onSurface: #201A14
- surfaceContainer: #F5EDE6, surfaceContainerHigh: #EFE8E0
- error: #B22D1A, errorContainer: #FFDAD4
- outline: #83786F, outlineVariant: #D4C8BD

**Dark scheme (key roles):**
- primary: #F5BB84, onPrimary: #4A2800
- primaryContainer: #6D4422, onPrimaryContainer: #FFDDB8
- secondary: #DCC3AD, onSecondary: #271A0D
- secondaryContainer: #534434, onSecondaryContainer: #F9DFC8
- tertiary: #C7CB97, tertiaryContainer: #464A22
- background/surface: #17120D, onBackground/onSurface: #EDE0D5
- surfaceContainer: #2E2620, surfaceContainerHigh: #39312A
- error: #FFB4A9, errorContainer: #93000A
- outline: #9D9189, outlineVariant: #4E453D

**Migration from current values:**
- FocalAccent (#C8956C) → MaterialTheme.colorScheme.primary
- FocalPrimary (#646CFF purple) → removed entirely, replaced by warm primary
- LightBackground (#F5F0E8) → background (#FFF8F4)
- DarkBackground (#1A1A2E navy) → background (#17120D warm brown)
- DigestBlue (#2E86DE) → tertiary
- NoiseSurface → surfaceContainerLow
- All .copy(alpha = X) → proper M3 roles (onSurfaceVariant, outlineVariant, surfaceContainer levels)

**Files changed:** Color.kt (rewrite), Theme.kt (full scheme with all roles)

### Typography

Complete the M3 type scale from 4 styles to all 15. Sizes chosen to match Focal's current visual proportions.

| Role | Weight | Size | Line Height | Letter Spacing |
|------|--------|------|-------------|----------------|
| displayLarge | 400 | 36sp | 44sp | 0sp |
| displayMedium | 400 | 30sp | 38sp | 0sp |
| displaySmall | 400 | 24sp | 32sp | 0sp |
| headlineLarge | 700 | 24sp | 32sp | 0sp |
| headlineMedium | 600 | 20sp | 28sp | 0sp |
| headlineSmall | 600 | 17sp | 24sp | 0sp |
| titleLarge | 600 | 18sp | 26sp | 0sp |
| titleMedium | 600 | 16sp | 24sp | 0.15sp |
| titleSmall | 600 | 14sp | 20sp | 0.1sp |
| bodyLarge | 400 | 16sp | 24sp | 0.5sp |
| bodyMedium | 400 | 14sp | 20sp | 0.25sp |
| bodySmall | 400 | 12sp | 16sp | 0.4sp |
| labelLarge | 600 | 14sp | 20sp | 0.1sp |
| labelMedium | 600 | 12sp | 16sp | 0.5sp |
| labelSmall | 700 | 11sp | 16sp | 0.5sp |

Hardcoded `letterSpacing = 2.sp` in screen files will use labelSmall from the type scale (0.5sp spacing). The SectionHeader component and category labels (e.g., "FINANCE", "MATTERS TO YOU") keep explicit `letterSpacing = 2.sp` as an intentional brand choice — this is a Focal design decision, not an M3 override, applied only to uppercase section headers via the SectionHeader composable.

**Files changed:** Type.kt (rewrite)

### Shapes

Define MaterialTheme.shapes with M3 corner scale. Replace all hardcoded RoundedCornerShape values.

| Token | Value | Used for |
|-------|-------|----------|
| extraSmall | 4.dp | Chips, badges |
| small | 8.dp | Text fields, menus |
| medium | 12.dp | Cards (TopicCard, PulseCard, wizard templates, StepCard) |
| large | 16.dp | FABs, navigation drawer |
| extraLarge | 28.dp | Bottom sheets, dialogs |

Mapping from current hardcoded values:
- RoundedCornerShape(10.dp) → shapes.small
- RoundedCornerShape(12.dp) → shapes.medium
- RoundedCornerShape(14.dp) → shapes.medium
- RoundedCornerShape(24.dp) → shapes.extraLarge or CircleShape for buttons

**Files changed:** new Shape.kt, Theme.kt (add shapes param)

## Tier 2: Screen Migration + Accessibility

### Per-Screen Color Migration

Every screen that references FocalAccent, hardcoded Color(), or .copy(alpha) gets updated to use MaterialTheme.colorScheme roles.

**DigestScreen:** FocalAccent → primary, .copy(alpha=0.5f) → onSurfaceVariant, hardcoded letterSpacing → use labelSmall style

**TopicCard:** FocalAccent → primary (3 instances), .copy(alpha) → onSurfaceVariant/outlineVariant, 14.dp → shapes.medium, 11.sp/8.sp hardcoded → labelSmall/bodySmall from theme

**TopicDetailScreen:** FocalAccent → primary, .copy(alpha=0.5f) → surfaceVariant, 2.sp letterSpacing → labelSmall

**PulseScreen:** FocalAccent → primary, 24.dp button shape → shapes.extraLarge, 2.sp letterSpacing → labelSmall

**PulseCard:** FocalAccent → primary, 14.dp → shapes.medium, 2.sp letterSpacing → labelSmall

**PulseDetailScreen:** 10.dp → shapes.small, 12.dp → shapes.medium, 2.sp letterSpacing → labelSmall

**PulseWizard:** FocalAccent (6 instances) → primary/primaryContainer, .copy(alpha=0.08f) → primaryContainer, BorderStroke FocalAccent → primary, 12.dp/10.dp → shapes.medium/small

**TuneScreen:** FocalAccent → primary, .copy(alpha) → proper roles, 12.dp → shapes.medium, emoji → Material Icons (Lock, AutoAwesome, VolumeOff)

**SetupScreen:** 12.dp → shapes.medium

**SectionHeader:** keeps 2.sp letterSpacing as intentional brand choice for uppercase headers, use labelSmall style as base, 8.dp padding kept

**AppIcon:** .copy(alpha=0.1f) → outlineVariant, .copy(alpha=0.6f) → onSurfaceVariant, magic 0.38f multiplier → labelSmall fontSize from theme

### Accessibility Fixes

**Touch targets (5 fixes):**
- TopicCard app icons: 28.dp icon inside 48.dp Modifier.minimumInteractiveComponentSize()
- PulseCard app icons: 18.dp → 24.dp icon with 48.dp touch area
- TopicDetailScreen app icons: 36.dp → 48.dp touch
- TuneScreen MaN pills: wrap in Modifier.minimumInteractiveComponentSize()
- Badge pills: decorative only, no touch target needed

**Missing contentDescription (15+ fixes):**
- Live indicator dots → contentDescription = "Recently updated"
- Category bullet indicators → decorative, semantics(mergeDescendants)
- App icon rows → contentDescription = "$appName icon"
- MaN pill buttons → contentDescription = "Set $appName to $category"
- Status badge pills → contentDescription = badge text
- Wizard template cards → semantics role description

**Emoji → Material Icons (3 replacements in TuneScreen):**
- Emoji 🔐 → Icons.Outlined.Lock
- Emoji ✨ → Icons.Outlined.AutoAwesome
- Emoji 🔇 → Icons.Outlined.VolumeOff

## Tier 3: Polish

### Motion

**Screen transitions (FocalNavigation.kt):**

Tab-to-tab (Today ↔ Pulse ↔ All ↔ Tune): Fade through — fadeOut 90ms with accelerate easing, fadeIn 210ms with decelerate easing. Peer destinations don't slide.

Parent-to-child (Pulse → PulseDetail, Today → TopicDetail): Shared axis Z — enter: scaleIn(0.8f→1.0f) + fadeIn, exit: scaleOut(1.0f→1.1f) + fadeOut. 300ms emphasized easing. Shows depth.

Forward nav (Tune → Setup): Slide in from right — slideInHorizontally + fadeIn, 300ms emphasized decelerate. Back: reverse.

**Component animations:**

LazyVerticalGrid/LazyColumn items: AnimatedVisibility with fadeIn + slideInVertically on first appearance. 50ms stagger between items.

Widget state changes (PulseCard): animateContentSize() on headline text when value changes.

Badge pills (PulseCard, TopicCard): AnimatedVisibility with fadeIn + expandHorizontally on first appearance.

LIVE indicator: Pulsing alpha animation (1.0 → 0.4 → 1.0, infinite repeat, 2s period) on the dot and "LIVE" text.

Wizard step transitions: AnimatedContent with slideInHorizontally (forward direction) / slideOutHorizontally (back direction) when switching between steps 1→2→3.

### Adaptive Layout

Use `calculateWindowSizeClass()` from `material3-window-size-class` dependency.

**Compact (0–599dp):** Current layout — bottom NavigationBar, single-column (Today/Tune), 2-column grid (Pulse). No changes.

**Medium (600–839dp):** NavigationRail replaces bottom NavigationBar (left side). Pulse grid: 3 columns. Today/Tune content: max-width 600dp centered. PulseDetail/TopicDetail: wider content area.

**Expanded (840dp+):** NavigationRail with labels. List-detail canonical layout: Today shows topic list on left (320dp) + topic detail on right. Pulse grid: 4 columns. Content max-width: 1040dp.

**Implementation:** FocalNavigation.kt gets a `when(windowSizeClass.widthSizeClass)` block that switches between NavigationBar and NavigationRail. Screen composables receive extra width naturally — no changes needed in individual screens except Pulse grid column count.

**New dependency:** `implementation("androidx.compose.material3:material3-window-size-class:$m3Version")`

## File Structure

### Modified files (Tier 1):
- `ui/theme/Color.kt` — complete rewrite with M3 seed-generated palette
- `ui/theme/Type.kt` — complete rewrite with all 15 styles
- `ui/theme/Theme.kt` — full colorScheme with all roles, add shapes param

### New files (Tier 1):
- `ui/theme/Shape.kt` — M3 shape scale definition

### Modified files (Tier 2 — 10 screens + 2 components):
- `ui/digest/DigestScreen.kt`
- `ui/digest/TopicCard.kt`
- `ui/digest/TopicDetailScreen.kt`
- `ui/pulse/PulseScreen.kt`
- `ui/pulse/PulseCard.kt`
- `ui/pulse/PulseDetailScreen.kt`
- `ui/pulse/PulseWizard.kt`
- `ui/tune/TuneScreen.kt`
- `ui/setup/SetupScreen.kt`
- `ui/components/SectionHeader.kt`
- `ui/components/AppIcon.kt`

### Modified files (Tier 3):
- `ui/navigation/FocalNavigation.kt` — screen transitions + adaptive nav
- `ui/pulse/PulseScreen.kt` — grid item animation + adaptive columns
- `ui/pulse/PulseCard.kt` — animateContentSize, badge animation, LIVE pulse
- `ui/digest/DigestScreen.kt` — item animation
- `ui/digest/TopicCard.kt` — badge animation
- `ui/pulse/PulseWizard.kt` — step transition animation

### New dependency (Tier 3):
- `androidx.compose.material3:material3-window-size-class`

## Priority Order

1. Color.kt + Theme.kt + Shape.kt (foundation — everything else depends on this)
2. Type.kt (typography foundation)
3. Screen migration (all 10 screens + 2 components — use new theme tokens)
4. Accessibility fixes (contentDescriptions, touch targets, emoji→icons)
5. Motion (screen transitions, component animations)
6. Adaptive layout (window size classes, NavigationRail)
