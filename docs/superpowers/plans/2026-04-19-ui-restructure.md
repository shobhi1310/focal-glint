# UI Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure all screens to match the reference mockup — personalized greeting, enriched story cards with category tags and app icons, quiet summary detail page, tune screen with explanation cards and compact pill toggles, developer settings split.

**Architecture:** Incremental per-screen rewrites preserving existing ViewModels where possible. New shared utility components (RelativeTime, AppCategory, SectionHeader) built first, then each screen rewritten independently. Navigation rename (Today/All/Tune) applied last to avoid breaking intermediate builds.

**Tech Stack:** Jetpack Compose (Material 3), Kotlin, Hilt, existing Room entities unchanged

---

## Task 1: Shared Utility Components

Create reusable components that multiple screens depend on. No UI changes yet — just utilities.

**Files to create:**
- `app/src/main/java/com/focal/ui/components/RelativeTime.kt`
- `app/src/main/java/com/focal/ui/components/AppCategory.kt`
- `app/src/main/java/com/focal/ui/components/SectionHeader.kt`
- `app/src/test/java/com/focal/ui/components/RelativeTimeTest.kt`
- `app/src/test/java/com/focal/ui/components/AppCategoryTest.kt`

- [ ] **Step 1: Create RelativeTime utility with tests**

Create `app/src/test/java/com/focal/ui/components/RelativeTimeTest.kt`:
```kotlin
package com.focal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private fun now() = System.currentTimeMillis()

    @Test
    fun `just now for under 1 minute`() {
        assertEquals("just now", formatRelativeTime(now() - 30_000, now()))
    }

    @Test
    fun `minutes ago`() {
        assertEquals("5 min ago", formatRelativeTime(now() - 5 * 60_000, now()))
    }

    @Test
    fun `1 hour ago`() {
        assertEquals("1h ago", formatRelativeTime(now() - 60 * 60_000, now()))
    }

    @Test
    fun `several hours ago`() {
        assertEquals("3h ago", formatRelativeTime(now() - 3 * 60 * 60_000, now()))
    }

    @Test
    fun `yesterday`() {
        assertEquals("yesterday", formatRelativeTime(now() - 25 * 60 * 60_000, now()))
    }
}
```

Create `app/src/main/java/com/focal/ui/components/RelativeTime.kt`:
```kotlin
package com.focal.ui.components

fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = nowMs - timestampMs
    val diffMinutes = diffMs / 60_000
    val diffHours = diffMs / 3_600_000

    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes} min ago"
        diffHours < 24 -> "${diffHours}h ago"
        else -> "yesterday"
    }
}
```

- [ ] **Step 2: Create AppCategory utility with tests**

Create `app/src/test/java/com/focal/ui/components/AppCategoryTest.kt`:
```kotlin
package com.focal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoryTest {

    @Test
    fun `whatsapp is personal`() {
        assertEquals("PERSONAL", getAppCategory("com.whatsapp"))
    }

    @Test
    fun `telegram is personal`() {
        assertEquals("PERSONAL", getAppCategory("org.telegram.messenger"))
    }

    @Test
    fun `slack is work`() {
        assertEquals("WORK", getAppCategory("com.Slack"))
    }

    @Test
    fun `cred is finance`() {
        assertEquals("FINANCE", getAppCategory("com.cred.android"))
    }

    @Test
    fun `swiggy is logistics`() {
        assertEquals("LOGISTICS", getAppCategory("in.swiggy.android"))
    }

    @Test
    fun `unknown is general`() {
        assertEquals("GENERAL", getAppCategory("com.unknown.app"))
    }

    @Test
    fun `messages SMS is personal`() {
        assertEquals("PERSONAL", getAppCategory("com.google.android.apps.messaging"))
    }
}
```

Create `app/src/main/java/com/focal/ui/components/AppCategory.kt`:
```kotlin
package com.focal.ui.components

fun getAppCategory(packageName: String): String {
    return when (packageName) {
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.android.phone" -> "PERSONAL"

        "com.Slack",
        "com.github.android",
        "com.microsoft.teams",
        "com.microsoft.office.outlook",
        "com.linkedin.android" -> "WORK"

        "com.cred.android",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa",
        "in.org.npci.upiapp" -> "FINANCE"

        "in.swiggy.android",
        "com.application.zomato",
        "com.ubercab",
        "com.rapido.passenger" -> "LOGISTICS"

        else -> "GENERAL"
    }
}
```

- [ ] **Step 3: Create SectionHeader composable**

Create `app/src/main/java/com/focal/ui/components/SectionHeader.kt`:
```kotlin
package com.focal.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = if (count != null) "$title · $count" else title,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
```

- [ ] **Step 4: Run tests and build**

```bash
./gradlew testDebugUnitTest --tests "com.focal.ui.components.*"
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add shared UI utilities — RelativeTime, AppCategory, SectionHeader"
```

---

## Task 2: Digest Screen Rewrite

Rewrite DigestScreen with personalized greeting, stats line, and section header. Requires Task 1 utilities.

**Files to modify:**
- `app/src/main/java/com/focal/ui/digest/DigestScreen.kt`
- `app/src/main/java/com/focal/ui/digest/DigestViewModel.kt`

- [ ] **Step 1: Update DigestViewModel to provide greeting data**

In `DigestViewModel.kt`, update `DigestUiState` to:
```kotlin
data class DigestUiState(
    val greeting: String = "",
    val dayTimeLabel: String = "",
    val stories: List<TopicEntity> = emptyList(),
    val mattersCount: Int = 0,
    val noiseCount: Int = 0,
    val totalNotifications: Int = 0,
    val isProcessing: Boolean = false
)
```

Add a greeting helper function inside the ViewModel:
```kotlin
    private fun buildGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour in 5..11 -> "Good morning"
            hour in 12..16 -> "Good afternoon"
            hour in 17..20 -> "Good evening"
            else -> "Good night"
        }
        return "$timeOfDay, Shubhankar."
    }

    private fun buildDayTimeLabel(): String {
        val sdf = java.text.SimpleDateFormat("EEEE · h:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date()).uppercase()
    }
```

Update the `combine` to populate these:
```kotlin
    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<DigestUiState> = combine(
        topicRepository.getStoryTopics(),
        topicRepository.getBriefing(),
        notificationRepository.countByCategory(ClassificationResult.MATTERS),
        notificationRepository.countByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount(),
        isProcessing
    ) { values ->
        val stories = values[0] as List<TopicEntity>
        val mattersCount = values[2] as Int
        val noiseCount = values[3] as Int
        val totalNotifications = values[4] as Int
        val processing = values[5] as Boolean

        DigestUiState(
            greeting = buildGreeting(),
            dayTimeLabel = buildDayTimeLabel(),
            stories = stories,
            mattersCount = mattersCount,
            noiseCount = noiseCount,
            totalNotifications = totalNotifications,
            isProcessing = processing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DigestUiState()
    )
```

Note: The `combine` with 6 flows uses the array overload. Keep the `@Suppress("UNCHECKED_CAST")`. Remove the unused `briefing` flow collection — the briefing is no longer shown as a paragraph.

- [ ] **Step 2: Rewrite DigestScreen**

Replace `DigestScreen.kt` entirely:
```kotlin
package com.focal.ui.digest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.components.SectionHeader
import com.focal.ui.theme.FocalAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(
    onTopicClick: (String) -> Unit = {},
    viewModel: DigestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.isProcessing,
        onRefresh = { viewModel.onRefresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Day/time label
            item {
                Text(
                    text = state.dayTimeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // Personalized greeting
            item {
                val parts = state.greeting.split(", ", limit = 2)
                Text(
                    text = buildAnnotatedString {
                        append(parts.getOrElse(0) { state.greeting })
                        if (parts.size > 1) {
                            append(", ")
                            withStyle(SpanStyle(color = FocalAccent)) {
                                append(parts[1])
                            }
                        }
                    },
                    style = MaterialTheme.typography.headlineLarge
                )
            }

            // Stats line
            item {
                Text(
                    text = buildAnnotatedString {
                        append("${state.totalNotifications} notifications. ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("${state.mattersCount} matter")
                        }
                        append(", ${state.noiseCount} noise hidden.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Section header
            if (state.stories.isNotEmpty()) {
                item {
                    SectionHeader(title = "MATTERS TO YOU", count = state.stories.size)
                }
            }

            if (state.isProcessing) {
                item {
                    Text(
                        text = "Refreshing your digest...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else if (state.stories.isEmpty() && state.totalNotifications == 0) {
                item {
                    Text(
                        text = "No notifications yet. Make sure notification access is enabled in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else if (state.stories.isEmpty()) {
                item {
                    Text(
                        text = "Processing notifications into stories...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.stories, key = { it.id }) { topic ->
                    TopicCard(
                        topic = topic,
                        onClick = { onTopicClick(topic.id) }
                    )
                }
            }
        }
    }
}
```

Add `import androidx.compose.ui.unit.sp` for the letterSpacing.

- [ ] **Step 3: Add FocalAccent color**

In `app/src/main/java/com/focal/ui/theme/Color.kt`, add:
```kotlin
val FocalAccent = Color(0xFFC8956C)
```

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: rewrite DigestScreen with greeting, stats line, and section header"
```

---

## Task 3: TopicCard Rewrite

Enriched story cards with category tags, summary, app icons, notification count.

**Files to modify:**
- `app/src/main/java/com/focal/ui/digest/TopicCard.kt`

- [ ] **Step 1: Rewrite TopicCard**

Replace `TopicCard.kt` entirely:
```kotlin
package com.focal.ui.digest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focal.data.db.entity.TopicEntity
import com.focal.ui.components.formatRelativeTime
import com.focal.ui.components.getAppCategory
import com.focal.ui.theme.FocalAccent
import org.json.JSONArray

@Composable
fun TopicCard(
    topic: TopicEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceApps = parseSourceApps(topic.sourceApps)
    val notificationCount = parseNotificationIds(topic.notificationIds).size
    val category = if (sourceApps.isNotEmpty()) {
        getAppCategory(topic.actionPackage ?: "")
    } else "GENERAL"

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Category tag row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "●",
                        color = FocalAccent,
                        fontSize = 8.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = "MATTERS · $category",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Text(
                    text = formatRelativeTime(topic.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Headline
            Text(
                text = topic.headline,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Summary
            Text(
                text = topic.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Footer: app icons + notification count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                    sourceApps.take(4).forEach { app ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = app.take(1).uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                if (notificationCount > 0) {
                    Text(
                        text = "$notificationCount notifications",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

private fun parseSourceApps(json: String): List<String> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}

private fun parseNotificationIds(json: String): List<String> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: rewrite TopicCard with category tags, summary, app icons, notification count"
```

---

## Task 4: Topic Detail Screen Rewrite

Quiet summary card, sources list with app icons + timestamps.

**Files to modify:**
- `app/src/main/java/com/focal/ui/digest/TopicDetailScreen.kt`

- [ ] **Step 1: Rewrite TopicDetailScreen**

Replace `TopicDetailScreen.kt` entirely with the new layout:

1. **Back navigation** — "‹ Today" text button
2. **Category tag + time** — "● PERSONAL · 34 MIN AGO" uppercase spaced
3. **Headline** — headlineMedium, bold
4. **Quiet Summary card** — Surface with "✨ QUIET SUMMARY" header + topic.summary body
5. **Sources section** — "SOURCES · N" SectionHeader + list of source notifications with circular app icon initial + app name + content preview + relative time

Key composables to create inside the file:
- `QuietSummaryCard(summary: String)` — styled card
- `SourceNotificationRow(notification: NotificationEntity)` — row with icon, app, content, time

Remove: `CategoryBadge`, `DetailCard`, `DetailRow`, old structured data rendering. Keep the action button if `actionPackage` exists (simple "Open in {app}" button).

The full code is approximately 200 lines. The implementer should:
- Import `SectionHeader`, `formatRelativeTime`, `getAppCategory`, `FocalAccent` from shared components
- Use `TopicDetailViewModel` as-is (it already provides `topic` and `notifications`)
- Parse `sourceApps` JSON for category detection
- Use `HorizontalDivider` between source notification rows

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: rewrite TopicDetailScreen with quiet summary and sources list"
```

---

## Task 5: All Notifications Screen Restyle

Match the new visual language — section headers, notification rows with app icons.

**Files to modify:**
- `app/src/main/java/com/focal/ui/all/AllNotificationsScreen.kt`
- `app/src/main/java/com/focal/ui/digest/CategorySection.kt`
- `app/src/main/java/com/focal/ui/digest/NotificationCard.kt`

- [ ] **Step 1: Restyle NotificationCard with app icon and relative time**

Rewrite `NotificationCard.kt` to show: circular app icon (first letter) + app name (bold) + content preview + relative time (right-aligned). Match the Sources row style from TopicDetailScreen.

- [ ] **Step 2: Restyle CategorySection with SectionHeader**

Update `CategorySection.kt` to use `SectionHeader` for the section label instead of the current colored label. Remove color/containerColor parameters — use the standard surface color.

- [ ] **Step 3: Update AllNotificationsScreen header**

Replace the header with:
- "All Notifications" in headlineLarge
- Stats line: "Last 24h · {total} notifications"
- Section headers: "MATTERS TO YOU · {count}" and "NOISE · {count}" using `SectionHeader`

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: restyle All notifications with section headers and app icon rows"
```

---

## Task 6: Tune Screen (Settings Rewrite)

Explanation cards, compact M/A/N pill toggles, app icons, developer settings split.

**Files to create:**
- `app/src/main/java/com/focal/ui/tune/TuneScreen.kt`
- `app/src/main/java/com/focal/ui/tune/TuneViewModel.kt`

**Files to modify:**
- `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`

- [ ] **Step 1: Create TuneViewModel**

The TuneViewModel has the same logic as SettingsViewModel (app override loading, debounced toggle, persist). Create `app/src/main/java/com/focal/ui/tune/TuneViewModel.kt` by copying the core logic from SettingsViewModel but with a cleaner state:

```kotlin
data class TuneAppItem(
    val packageName: String,
    val appName: String,
    val notificationCount: Int,
    val systemDefault: String?,
    val userOverride: String?,
    val isUserSet: Boolean
)

data class TuneUiState(
    val apps: List<TuneAppItem> = emptyList(),
    val snackbarMessage: String? = null
)
```

The `isUserSet` field is `true` when `userOverride != null` — drives the "Set by you" vs "system default" label.

Keep the same debounce logic (3s), ClassificationWorker enqueue, and rule persistence.

- [ ] **Step 2: Create TuneScreen**

Create `app/src/main/java/com/focal/ui/tune/TuneScreen.kt` with:

1. **Header:** "Tune the noise." (headlineLarge) + subtitle
2. **Explanation cards row:** 3 cards in a `Row` — Matters (🔒), Auto (✨), Noise (🔇) with title + description. Use `Surface` with rounded corners.
3. **App list:** "YOUR APPS · {count}" SectionHeader, then `LazyColumn` of app rows
4. **Each app row:** Circular icon (first letter) + app name + lock icon if user-set + "Set by you" / "system default" subtitle + compact M/A/N pill toggle
5. **M/A/N pill:** Custom composable — `Row` of 3 `Box` items inside a rounded border. Selected = filled circle with `FocalAccent` (for M/N) or dark (for A). Unselected = muted text.
6. **Developer Settings link:** `TextButton("Developer Settings")` at the bottom, navigates to Setup screen.
7. **Snackbar** at bottom for save feedback.

- [ ] **Step 3: Wire TuneScreen into navigation**

In `FocalNavigation.kt`, replace the Settings composable route with TuneScreen:
```kotlin
            composable(Screen.Settings.route) {
                TuneScreen(
                    onNavigateToDevSettings = {
                        navController.navigate(Screen.Setup.route)
                    }
                )
            }
```

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add TuneScreen with explanation cards, compact pill toggles, dev settings link"
```

---

## Task 7: Navigation Rename + Cleanup

Final pass — rename tabs, clean up old files.

**Files to modify:**
- `app/src/main/java/com/focal/ui/navigation/Screen.kt`
- `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`

- [ ] **Step 1: Rename navigation tabs**

In `Screen.kt`, update:
```kotlin
    data object Digest : Screen("digest", "Today", Icons.Default.Star)
    data object Settings : Screen("settings", "Tune", Icons.Default.Settings)
```

Only the `title` changes — routes stay the same to avoid breaking navigation state.

- [ ] **Step 2: Update FocalNavigation bottom bar items**

The `bottomNavItems` list already references `Screen.Digest` and `Screen.Settings` — the title change propagates automatically.

Verify the Setup screen (Developer Settings) is accessible from TuneScreen via navigation.

- [ ] **Step 3: Verify full build and all tests**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: rename nav tabs — Digest→Today, Settings→Tune"
```

---

## Task 8: On-Device Verification

- [ ] **Step 1: Install and launch**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Verify Today (Digest) screen**
- Greeting: "Good morning/afternoon/evening, Shubhankar." with name in gold
- Day/time label in uppercase
- Stats line with matters count bold
- "MATTERS TO YOU · N" section header
- Story cards with category tags, summary, app icons, notification count

- [ ] **Step 3: Verify topic detail**
- Tap a story card
- Category tag + time at top
- Large headline
- Quiet Summary card
- Sources list with app icons + relative time

- [ ] **Step 4: Verify All page**
- Section headers in uppercase spaced style
- Notification rows with app icons

- [ ] **Step 5: Verify Tune screen**
- "Tune the noise." header
- 3 explanation cards
- App list with M/A/N pills
- "Developer Settings" link at bottom → opens Setup screen

- [ ] **Step 6: Commit and push**

```bash
git push
```

---

## Summary

8 tasks:
1. **Shared utilities** — RelativeTime, AppCategory, SectionHeader (with tests)
2. **Digest Screen** — greeting, stats, section header
3. **TopicCard** — category tags, summary, app icons, count
4. **Topic Detail** — quiet summary card, sources list
5. **All Notifications** — restyled with section headers and icon rows
6. **Tune Screen** — explanation cards, compact pills, dev settings link
7. **Navigation rename** — Today/All/Tune
8. **On-device verification**
