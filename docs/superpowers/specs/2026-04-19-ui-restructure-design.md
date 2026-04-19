# UI Restructure — Structure-First Redesign

**Goal:** Restructure all screens (Digest, Topic Detail, All, Settings) to match the reference mockup layout — personalized greeting, enriched story cards, quiet summary, sources list, tune screen with explanation cards and compact pill toggles. Theme change is deferred to a separate follow-up.

---

## 1. Bottom Navigation

Rename tabs but keep all three:
- "Digest" → **"Today"**
- "All" stays **"All"**
- "Settings" → **"Tune"**

Update `Screen.kt` route names, titles, and icons accordingly.

## 2. Digest Screen ("Today")

### Header Section
Replace the current "Your Digest" + briefing paragraph with:

1. **Day/time line** — "TUESDAY · 9:41" in uppercase spaced lettering (`letterSpacing = 2.sp`)
2. **Personalized greeting** — "Good morning, Shubhankar." where the name is in gold/amber accent color. Time-of-day aware: "Good morning" (5AM-12PM), "Good afternoon" (12PM-5PM), "Good evening" (5PM-9PM), "Good night" (9PM-5AM). User's first name from device owner or a hardcoded fallback for now.
3. **Stats line** — "{total} notifications since you slept. **{mattersCount} matter**, {noiseCount} worth a glance." where matters count is bold.

### Section Header
Below the greeting: **"MATTERS TO YOU · {count}"** in uppercase spaced lettering with count.

### Story Cards (TopicCard)
Each card shows:
1. **Category tag row** — "● MATTERS · {category}" + relative time ("34 min ago") right-aligned. The dot is gold/amber. Category is derived from the dominant app type in the topic (PERSONAL for messaging apps, FINANCE for bank/payment, WORK for Slack/GitHub, GENERAL otherwise).
2. **Headline** — Large bold text (headlineSmall or titleLarge)
3. **Summary** — 2-line max body text below headline (the topic summary, not a separate briefing)
4. **Footer row** — Left: circular app icons for each source app. Right: "{N} notifications" count text.

### Remove
- The LLM briefing paragraph — replaced by structured greeting + stats
- The "X promotional notifications hidden" footer — fold into the stats line as "worth a glance"

### Pull-to-refresh
Keep the existing pull-to-refresh with "Refreshing your digest..." behavior.

## 3. Topic Detail Screen

### Layout (top to bottom)
1. **Back navigation** — "‹ Digest" (or "‹ Today")
2. **Category tag + time** — "● PERSONAL · 34 MIN AGO" in uppercase spaced lettering
3. **Headline** — Large, bold (headlineMedium)
4. **Quiet Summary card** — Rounded card with:
   - Header: "✨ QUIET SUMMARY" in uppercase spaced lettering
   - Body: LLM-generated detailed summary text
5. **Sources section** — "SOURCES · {N}" header, then a list of source notifications:
   - Each row: circular app icon + app name (bold) + content preview (muted) + relative time (right-aligned)
   - Divider between rows

### Remove
- The old CategoryBadge composable
- The old detail_json / structured data card (replaced by quiet summary)
- Action buttons (deferred — "Suggested Next Steps" requires LLM intent extraction, future work)

## 4. All Notifications Screen

Redesign to match the new visual language:

### Header
1. **"All Notifications"** in large bold (headlineLarge)
2. **Stats line** — "Last 24h · {total} notifications"

### Sections
Keep the 2-section layout (MATTERS expanded, NOISE collapsed) but restyle:
1. **Section headers** — "MATTERS TO YOU · {count}" and "NOISE · {count}" in uppercase spaced lettering
2. **Notification rows** — Each row: circular app icon + app name + title (bold) + content preview + relative time. Match the Sources list style from Topic Detail.
3. **Collapsible sections** — Tap header to expand/collapse (keep existing CategorySection behavior but restyle)

## 5. Tune Screen (Settings)

### Header
1. **"Tune the noise."** — headlineLarge, bold
2. **Subtitle** — "Three tiers. Pick by hand, or let the model learn from how you read."

### Explanation Cards Row
Three cards in a horizontal row (equal width):
- **Matters** — 🔒 icon, "Matters" title, "Always surface. You said so." description
- **Auto** — ✨ icon, "Auto" title, "Let Focal decide what's worth telling you." description
- **Noise** — 🔇 icon, "Noise" title, "Silenced. Bundled into a footnote." description

Cards are informational only — not tappable.

### App List
**"YOUR APPS · {count}"** section header in uppercase spaced lettering.

Each app row:
1. **Left:** Circular app icon (emoji placeholder for now) + app name (bold) + lock icon 🔒 if user-set + subtitle ("Set by you" if user override exists, "system default" otherwise)
2. **Right:** Compact **M / A / N** pill toggle
   - Three letters in a rounded pill container
   - Selected letter: filled circle with gold/amber background (for M or N user-set) or dark background (for A)
   - Unselected letters: muted text, no fill

### Debounce behavior
Keep the existing 3-second debounce save + ClassificationWorker enqueue.

## 6. Shared Components

### Relative time formatter
Create a utility: `fun formatRelativeTime(timestampMs: Long): String` that returns "2m", "34 min ago", "3h ago", "yesterday".

### App category detector
Extend the existing `DetailTemplates.detectAppType()` or create a new utility that maps package names to display categories:
- `com.whatsapp`, `org.telegram.messenger`, `com.discord` → "PERSONAL"
- `com.Slack`, `com.github.android` → "WORK"
- `com.cred.android`, bank SMS senders → "FINANCE"
- Logistics apps → "LOGISTICS"
- Default → "GENERAL"

### Section header composable
Reusable `SectionHeader(title: String, count: Int?)` that renders uppercase spaced text.

## 7. Files to Change

| File | Change |
|------|--------|
| `Screen.kt` | Rename routes/titles: Digest→Today, Settings→Tune |
| `FocalNavigation.kt` | Update imports and tab references |
| `DigestScreen.kt` | Major rewrite — greeting, stats, section header |
| `DigestViewModel.kt` | Add greeting data (time of day, matters/noise counts) |
| `TopicCard.kt` | Major rewrite — category tags, summary, app icons, notification count |
| `TopicDetailScreen.kt` | Major rewrite — quiet summary card, sources list |
| `TopicDetailViewModel.kt` | No structural change, may need source notification data |
| `AllNotificationsScreen.kt` | Restyle to match new visual language |
| `CategorySection.kt` | Restyle section headers and notification rows |
| `SettingsScreen.kt` | Major rewrite → TuneScreen — explanation cards, compact pills, app icons |
| `SettingsViewModel.kt` | Add "Set by you" / "system default" label logic |
| `RelativeTime.kt` | New — relative time formatter utility |
| `AppCategory.kt` | New — package name to display category mapping |
| `SectionHeader.kt` | New — reusable uppercase spaced section header |
