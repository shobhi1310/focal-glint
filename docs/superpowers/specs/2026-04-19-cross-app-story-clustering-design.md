# Cross-App Story Clustering

**Goal:** Merge notifications from multiple apps into unified stories using LLM-based semantic grouping, with incremental processing and a fixed daily window.

**Core insight:** Stories are about people and events, not apps. "Mom called twice — wants to confirm Sunday lunch" should pull from Phone + WhatsApp as one story, not two separate app-level cards.

---

## 1. Architecture

Three-phase pipeline replacing the current app+sender grouping in TopicEngine:

1. **Dedup** (code-level) — Remove exact duplicate content before the LLM sees it. Truecaller re-showing the same SMS text, or multiple notification updates for the same event, are filtered by content similarity (>90% match within the same time window).

2. **LLM Grouping** (one call) — Feed all unprocessed notifications + existing topic summaries to Gemma 4 E2B. The model returns semantic group assignments. It handles fuzzy name matching ("Subhankar Bhadra" = "Subhankar B."), cross-app correlation (ICICI SMS + Truecaller alert), and conceptual links (Amazon order email + delivery SMS).

3. **Narrative Generation** (per new/changed group) — For each group that gained new notifications, regenerate the one-sentence headline via LLM. Then regenerate the daily briefing from all headlines.

Processing is **incremental** — only new/unprocessed notifications are sent as full content. Existing topics are sent as one-line summaries for context. This keeps token usage low (existing stories are ~20 tokens each vs ~100 tokens per full notification).

**Pull-to-refresh = full rebuild** — Resets all processed flags, deletes all topics, re-groups everything from scratch.

## 2. Daily Window

**Fixed 2 AM to 2 AM window** (not a rolling 24-hour sliding window).

- A scheduled WorkManager periodic job fires at 2 AM daily
- It purges all topics and resets processed flags
- Notifications outside the current day window are purged
- When the user opens the app after 2 AM, the digest is clean — new day, fresh topics

This ensures the digest reflects "today" as a coherent unit, not a constantly shifting window where morning notifications silently disappear in the afternoon.

## 3. Incremental Processing Flow

### Automatic Worker (triggered 30s after each notification)

1. Determine current day window boundaries (last 2 AM to next 2 AM)
2. Get unprocessed notifications: `processedForTopics = false` AND within current day window
3. If no unprocessed notifications, skip
4. Get existing topics (headline + source notification IDs)
5. **Dedup pass:** For each unprocessed notification, check if its content is >90% similar to any other notification (processed or unprocessed) in the current window. If so, mark it processed and skip it — it's a duplicate.
6. **LLM grouping call** (one call):
   ```
   You are grouping notifications into stories. Each story is about one event or person.

   Existing stories:
   [S1] "Mom called twice — wants to confirm Sunday lunch" (Phone, WhatsApp)
   [S2] "₹44K charged on ICICI card at Amazon" (Messages, Truecaller)

   New notifications:
   [N1] WhatsApp — Mom: "are you bringing Maya?"
   [N2] Gmail — Amazon: "Your order for iPhone case has shipped"
   [N3] Splitwise — Rahul added expense "Dinner ₹1200"

   For each new notification, either assign it to an existing story or group new notifications into new stories.
   Return JSON only: {"assign": {"N1": "S1", "N2": "S2"}, "new_stories": [["N3"]]}
   ```
7. Parse response — assign notifications to existing stories or create new groups
8. For stories that gained new notifications: regenerate headline via `buildNarrativePrompt` with ALL notifications in that story (processed + new)
9. For new story groups: generate headline from scratch
10. Mark all processed notifications as `processedForTopics = true`
11. Regenerate daily briefing from all current topic headlines
12. Save topics

### Pull-to-Refresh (full rebuild)

1. Set `processedForTopics = false` on ALL notifications in current day window
2. Delete all topics
3. Run the full pipeline — all notifications are "new," no existing stories
4. This is the user's "nuclear option" to fix bad groupings

### 2 AM Scheduled Job

1. Delete all topics
2. Delete (or ignore) notifications outside the new day window
3. `processedForTopics` flags are effectively reset since old notifications are purged
4. Uses WorkManager `PeriodicWorkRequest` with flex window around 2 AM

## 4. Database Changes

### NotificationEntity — add processed flag

Add column:
```kotlin
@ColumnInfo(name = "processed_for_topics")
val processedForTopics: Boolean = false
```

Migration v4→v5:
```sql
ALTER TABLE notifications ADD COLUMN processed_for_topics INTEGER NOT NULL DEFAULT 0
```

### NotificationDao — new queries

```kotlin
@Query("SELECT * FROM notifications WHERE processed_for_topics = 0 AND posted_at > :since AND posted_at < :until AND is_summary = 0 AND category = 'matters'")
suspend fun getUnprocessedMatters(since: Long, until: Long): List<NotificationEntity>

@Query("UPDATE notifications SET processed_for_topics = 1 WHERE id IN (:ids)")
suspend fun markProcessedForTopics(ids: List<String>)

@Query("UPDATE notifications SET processed_for_topics = 0 WHERE posted_at > :since AND posted_at < :until")
suspend fun resetProcessedFlags(since: Long, until: Long)
```

### TopicEntity — add source tracking

The existing `notificationIds` JSON array already tracks which notifications belong to a topic. No schema change needed — just ensure the TopicEngine updates it when merging new notifications into existing stories.

## 5. Dedup Logic

Content similarity check using normalized Levenshtein or simpler approach:

```kotlin
fun isDuplicate(a: NotificationEntity, b: NotificationEntity): Boolean {
    if (a.id == b.id) return false
    val contentA = (a.bigText ?: a.content).lowercase().trim()
    val contentB = (b.bigText ?: b.content).lowercase().trim()
    if (contentA.isBlank() || contentB.isBlank()) return false
    // Exact or near-exact match (one contains the other)
    return contentA == contentB ||
        contentA.contains(contentB) ||
        contentB.contains(contentA)
}
```

This catches: Truecaller re-showing SMS text, notification updates (same key, slightly different content), cross-app forwarding of identical content.

Not a fuzzy similarity score — that's what the LLM grouping handles.

## 6. LLM Grouping Prompt

### Format

```
You are grouping notifications into stories. Each story is about one person, event, or topic.
Group notifications that are about the same thing — even if from different apps.
"Subhankar Bhadra" and "Subhankar B." are the same person.
A bank SMS and a caller ID alert about the same transaction are one story.

{existing_stories_section}

New notifications:
[N1] {appName} — {title}: {content.take(150)}
[N2] {appName} — {title}: {content.take(150)}
...

For each new notification, assign to an existing story or create new stories.
Return JSON only: {"assign": {"N1": "S1"}, "new_stories": [["N2", "N3"], ["N4"]]}
```

### When no existing stories (full rebuild)

```
You are grouping notifications into stories. Each story is about one person, event, or topic.
Group notifications that are about the same thing — even if from different apps.

Notifications:
[1] {appName} — {title}: {content.take(150)}
[2] {appName} — {title}: {content.take(150)}
...

Group them by event/person. Return JSON only: [[1, 3], [2, 5, 7], [4], [6]]
```

### Parsing

Parse the JSON response. On failure, fall back to the current behavior (group by app + sender). The LLM grouping is an enhancement — if it fails, the old logic still works.

## 7. Day Window Boundaries

```kotlin
fun getDayWindow(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    // If before 2 AM, the window started at 2 AM yesterday
    if (cal.get(Calendar.HOUR_OF_DAY) < 2) {
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    cal.set(Calendar.HOUR_OF_DAY, 2)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    val end = start + 24 * 60 * 60 * 1000L
    return start to end
}
```

## 8. Files to Change

| File | Change |
|------|--------|
| `TopicEngine.kt` | Major rewrite — incremental grouping pipeline with LLM merge |
| `PromptBuilder.kt` | Add `buildGroupingPrompt` (incremental) and `buildFullGroupingPrompt` (rebuild) |
| `LlmResponseParser.kt` | Add `parseGroupingResponse` and `parseFullGroupingResponse` |
| `NotificationEntity.kt` | Add `processedForTopics` column |
| `NotificationDao.kt` | Add unprocessed query, mark processed, reset flags |
| `NotificationRepository.kt` | Add corresponding methods |
| `FocalDatabase.kt` | Add MIGRATION_4_5 (or next version) |
| `DatabaseModule.kt` | Register new migration |
| `ClassificationWorker.kt` | Use day window boundaries instead of 24h rolling |
| `DailyResetWorker.kt` | New — scheduled 2 AM job to purge and reset |
| `FocalApplication.kt` | Schedule the daily reset worker on startup |

## 9. Testing Strategy

### Unit Tests (no device needed)

1. **Dedup logic** — verify `isDuplicate` catches exact matches, substring matches, and correctly allows different content through
2. **Day window boundaries** — verify `getDayWindow()` returns correct boundaries at various times (1 AM = yesterday's window, 3 AM = today's window, 11 PM = today's window)
3. **Grouping prompt construction** — verify incremental prompt includes existing stories + new notifications in correct format. Verify full rebuild prompt includes all notifications.
4. **Grouping response parsing** — verify JSON parsing for: valid assignment response, valid full-rebuild response, malformed JSON (falls back gracefully), empty assignments, all-new stories, all-assigned
5. **Incremental flow** — mock LLM. Send N1-N3, verify 3 topics created and notifications marked processed. Send N4, verify only N4 in the prompt with existing topics as context. Verify N4 merges into correct existing topic.
6. **Full rebuild flow** — mock LLM. Create topics from N1-N3. Trigger full rebuild. Verify all processed flags reset, all notifications re-sent, new topics generated.
7. **Processed flag management** — verify markProcessedForTopics only marks specified IDs, resetProcessedFlags only affects current day window

### On-Device Simulation Tests

Create a test utility class `TestNotificationInjector` that inserts fake `NotificationEntity` rows directly into Room (bypassing NotificationListenerService). This allows controlled testing without needing real notifications.

**Test scenarios:**

1. **Cross-app person merge**
   - Inject: Phone "Mom" missed call + WhatsApp "Mom" message about Sunday lunch
   - Trigger worker
   - Verify: ONE topic with both apps as sources, headline mentions Mom + Sunday lunch

2. **Fuzzy name match**
   - Inject: Gmail from "Subhankar Bhadra" + Slack from "Subhankar B."
   - Trigger worker
   - Verify: ONE topic (LLM correctly identifies same person)

3. **Financial cross-app merge**
   - Inject: Messages SMS "₹44,000 spent on ICICI Card" + Truecaller "₹44,000 ICICI Bank"
   - Trigger worker
   - Verify: ONE financial story, not two

4. **Dedup — identical content**
   - Inject: Messages SMS "OTP is 483921" + Truecaller showing exact same text
   - Trigger worker
   - Verify: Duplicate filtered, only one notification reaches LLM

5. **Incremental processing**
   - Inject N1, N2, N3 → trigger worker → verify 2 topics created, 3 marked processed
   - Inject N4 (related to topic 1) → trigger worker → verify N4 merged into topic 1, headline regenerated, only N4 was sent to LLM grouping

6. **Pull-to-refresh full rebuild**
   - After incremental test above, trigger pull-to-refresh
   - Verify: all processed flags reset, all 4 notifications re-grouped, same or better topics produced

7. **Day boundary reset**
   - Inject notifications, generate topics
   - Simulate 2 AM reset (call the purge method directly)
   - Verify: topics deleted, processed flags reset, digest is empty until new notifications arrive
