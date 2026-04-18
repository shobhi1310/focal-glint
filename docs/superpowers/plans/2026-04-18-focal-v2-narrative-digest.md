# Focal v2 — Narrative Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 4-class notification system with binary matters/noise classification and transform the Digest page into a narrative personal briefing with LLM-generated story sentences.

**Architecture:** Binary classification (matters/noise) via simplified rules + LLM. TopicEngine rewritten to cluster notifications into stories, generate one-sentence narratives per story via LLM, then produce a daily briefing paragraph. Digest UI stripped of category badges, shows briefing + plain story cards.

**Tech Stack:** LiteRT-LM, Gemma 3 1B, Room (migration v3→v4), Jetpack Compose, WorkManager

---

## Task 1: 2-Class Classification + DB Migration + Prompt Rewrite

This is the foundation — changes ClassificationResult, DefaultRules, PromptBuilder, LlmResponseParser, Classifier, and database migration. Everything else depends on this.

**Files to modify:**
- `app/src/main/java/com/focal/intelligence/ClassificationResult.kt`
- `app/src/main/java/com/focal/intelligence/DefaultRules.kt`
- `app/src/main/java/com/focal/intelligence/PromptBuilder.kt`
- `app/src/main/java/com/focal/intelligence/LlmResponseParser.kt`
- `app/src/main/java/com/focal/intelligence/Classifier.kt`
- `app/src/main/java/com/focal/data/db/FocalDatabase.kt`
- `app/src/main/java/com/focal/di/DatabaseModule.kt`
- `app/src/test/java/com/focal/intelligence/LlmResponseParserTest.kt`
- `app/src/test/java/com/focal/intelligence/PromptBuilderTest.kt`
- `app/src/test/java/com/focal/intelligence/RulesEngineTest.kt`
- `app/src/test/java/com/focal/intelligence/ClassifierTest.kt`

- [ ] **Step 1: Replace ClassificationResult constants**

Replace entire file:
```kotlin
package com.focal.intelligence

data class ClassificationResult(
    val category: String,
    val classifiedBy: String,
    val ruleId: String? = null,
    val confidence: Float = 1.0f,
    val reason: String? = null
) {
    companion object {
        const val MATTERS = "matters"
        const val NOISE = "noise"
        const val UNCATEGORIZED = "uncategorized"
    }
}
```

- [ ] **Step 2: Rewrite DefaultRules — simplified binary rules, no keyword rules**

Replace the `get()` function:
```kotlin
    fun get(): List<RuleEntity> = listOf(
        // Noise — promotional/marketing apps
        appRule("com.rapido.passenger", NOISE),
        appRule("com.ubercab", NOISE),
        appRule("com.olacabs.customer", NOISE),
        appRule("com.flipkart.android", NOISE),
        appRule("com.amazon.mShop.android.shopping", NOISE),
        appRule("net.one97.paytm", NOISE),
        appRule("com.phonepe.app", NOISE),
        appRule("com.google.android.apps.nbu.paisa", NOISE),
        appRule("com.myntra.android", NOISE),
        appRule("com.snapdeal.main", NOISE),
        appRule("com.dream11.fantasy.cricket", NOISE),

        // Matters — personal communication
        appRule("com.whatsapp", MATTERS),
        appRule("org.telegram.messenger", MATTERS),
        appRule("com.Slack", MATTERS),
        appRule("com.discord", MATTERS),
        appRule("com.google.android.apps.messaging", MATTERS),

        // Matters — financial
        appRule("com.cred.android", MATTERS),

        // Matters — logistics
        appRule("in.swiggy.android", MATTERS),
        appRule("com.application.zomato", MATTERS),
    )

    private fun appRule(packageName: String, category: String) = RuleEntity(
        type = "app_match",
        app = packageName,
        category = category,
        confidence = 0.8f,
        source = "system_default"
    )
```

Remove `appNoise`, `keywordUrgent`, `keywordActionable` helper functions. Just one `appRule` helper. Import `ClassificationResult.MATTERS` and `ClassificationResult.NOISE` as constants `MATTERS` and `NOISE`.

- [ ] **Step 3: Rewrite PromptBuilder — binary classification + narrative prompts**

Replace entire file with:
```kotlin
package com.focal.intelligence

import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.NotificationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PromptBuilder {

    fun buildClassificationPrompt(notification: NotificationEntity): String {
        val message = notification.bigText ?: notification.content
        return """Does this notification matter personally to the user, or is it generic/promotional?

Notification from ${notification.appName}:
"${notification.title}: ${message.take(200)}"

Answer with JSON only: {"matters": true, "reason": "..."} or {"matters": false, "reason": "..."}"""
    }

    fun buildNarrativePrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("You are a personal assistant briefing the user about their notifications.")
        sb.appendLine("Write ONE natural sentence summarizing what happened.")
        sb.appendLine("Be specific — include names, amounts, times, places.")
        sb.appendLine("Do NOT say \"Here's a summary\" or \"Summary:\" — just state what happened.")
        sb.appendLine("Write as if telling a friend.")
        sb.appendLine()
        sb.appendLine("Notifications:")
        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.appName} — ${notif.title}: ${content.take(150)}")
        }
        sb.appendLine()
        sb.appendLine("Write one sentence:")
        return sb.toString()
    }

    fun buildBriefingPrompt(storyNarratives: List<String>, noiseCount: Int): String {
        val sb = StringBuilder()
        sb.appendLine("Write a 2-3 line personal briefing for the user.")
        sb.appendLine("Mention the most important things first. Be concise and specific.")
        sb.appendLine("Do NOT use bullet points. Write natural sentences.")
        sb.appendLine()
        sb.appendLine("Today's stories:")
        storyNarratives.forEachIndexed { index, narrative ->
            sb.appendLine("- $narrative")
        }
        if (noiseCount > 0) {
            sb.appendLine()
            sb.appendLine("$noiseCount promotional/noise notifications were hidden.")
        }
        sb.appendLine()
        sb.appendLine("Write the briefing:")
        return sb.toString()
    }
}
```

- [ ] **Step 4: Update LlmResponseParser — parse binary matters/noise + narrative text**

Add new method and update VALID_CATEGORIES:
```kotlin
    private val VALID_CATEGORIES = listOf("matters", "noise")

    fun parseMattersClassification(raw: String): ClassificationResult? {
        return try {
            val jsonStr = extractJson(raw)
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                val matters = json.optBoolean("matters", false)
                val reason = if (json.has("reason")) json.getString("reason") else null
                return ClassificationResult(
                    category = if (matters) ClassificationResult.MATTERS else ClassificationResult.NOISE,
                    classifiedBy = "llm",
                    confidence = 0.8f,
                    reason = reason
                )
            }

            // Fallback: look for "matters" or "noise" keywords
            val text = raw.lowercase()
            val category = when {
                text.contains("\"matters\": true") || text.contains("\"matters\":true") -> ClassificationResult.MATTERS
                text.contains("\"matters\": false") || text.contains("\"matters\":false") -> ClassificationResult.NOISE
                text.contains("matters") && !text.contains("doesn't matter") && !text.contains("does not matter") -> ClassificationResult.MATTERS
                text.contains("noise") || text.contains("promotional") || text.contains("doesn't matter") -> ClassificationResult.NOISE
                else -> return null
            }

            ClassificationResult(category = category, classifiedBy = "llm", confidence = 0.6f)
        } catch (e: Exception) {
            Log.w("LlmResponseParser", "Failed to parse matters response: ${raw.take(200)}", e)
            null
        }
    }

    fun parseNarrative(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        // Remove any preamble like "Here's a summary:" or "Summary:"
        val cleaned = trimmed
            .removePrefix("Here's a summary:")
            .removePrefix("Here is a summary:")
            .removePrefix("Summary:")
            .removePrefix("Here's the summary:")
            .trim()
        if (cleaned.isBlank()) return null
        // Take just the first sentence/paragraph
        return cleaned.lines().firstOrNull { it.isNotBlank() }?.trim()
    }
```

Keep the old `parseClassification` method for backward compatibility but update `VALID_CATEGORIES`. Also update `normalizeCategory` to handle matters/noise.

- [ ] **Step 5: Update Classifier to use binary prompt**

In `Classifier.kt`, change the `classify` method to use the new binary prompt:
```kotlin
    suspend fun classify(notification: NotificationEntity): ClassificationResult {
        if (!inferenceProvider.isReady()) {
            return ClassificationResult(
                category = ClassificationResult.UNCATEGORIZED,
                classifiedBy = "pending"
            )
        }

        val prompt = PromptBuilder.buildClassificationPrompt(notification)

        return try {
            val response = inferenceProvider.generate(prompt, maxTokens = 100)
            LlmResponseParser.parseMattersClassification(response)
                ?: ClassificationResult(
                    category = ClassificationResult.UNCATEGORIZED,
                    classifiedBy = "llm",
                    reason = "Failed to parse"
                )
        } catch (e: Exception) {
            Log.e("Classifier", "LLM inference failed", e)
            ClassificationResult(
                category = ClassificationResult.UNCATEGORIZED,
                classifiedBy = "pending",
                reason = "LLM error: ${e.message}"
            )
        }
    }
```

Remove the `notificationRepository` and `ruleRepository` dependencies from the constructor — the binary prompt doesn't need app profiles or corrections context. Just the notification text.

- [ ] **Step 6: Database migration v3→v4**

In `FocalDatabase.kt`, add:
```kotlin
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE notifications SET category = 'matters' WHERE category IN ('urgent', 'actionable', 'digest')")
                db.execSQL("ALTER TABLE topics ADD COLUMN briefing_contribution TEXT")
            }
        }
```

Bump version to 4. Add migration to DatabaseModule.

- [ ] **Step 7: Update Classifier DI — remove unused dependencies**

In `IntelligenceModule.kt`, update `provideClassifier` to only inject `InferenceProvider`:
```kotlin
    @Provides
    @Singleton
    fun provideClassifier(inferenceProvider: InferenceProvider): Classifier {
        return Classifier(inferenceProvider)
    }
```

- [ ] **Step 8: Update DefaultRules seed version**

In `FocalApplication.kt`, bump `currentVersion` to 4.

- [ ] **Step 9: Fix all tests**

Update all test files to use `ClassificationResult.MATTERS` and `ClassificationResult.NOISE` instead of the old constants. Update PromptBuilder tests for the new prompt format. Update LlmResponseParser tests for `parseMattersClassification`. Update RulesEngine tests for binary categories.

- [ ] **Step 10: Verify build and tests**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: replace 4-class with binary matters/noise classification and narrative prompts"
```

---

## Task 2: TopicEngine Rewrite — Story Narratives + Daily Briefing

**Files to modify:**
- `app/src/main/java/com/focal/intelligence/TopicEngine.kt` — major rewrite
- `app/src/main/java/com/focal/data/db/entity/TopicEntity.kt` — add briefing_contribution
- `app/src/main/java/com/focal/data/db/dao/TopicDao.kt` — add briefing query

- [ ] **Step 1: Add briefing_contribution to TopicEntity**

Add after `isRead`:
```kotlin
    @ColumnInfo(name = "briefing_contribution")
    val briefingContribution: String? = null
```

- [ ] **Step 2: Rewrite TopicEngine.generateTopics()**

Replace the entire `generateTopics()` method and supporting methods. The new pipeline:

1. Get all "matters" notifications (filter out noise and uncategorized)
2. Group by app, then sub-group by sender/conversation
3. For each group with 1+ notifications: generate a one-sentence narrative via LLM
4. If LLM not ready: use a simple template "X messages from Y"
5. Build TopicEntity with narrative as headline
6. Generate daily briefing from all narratives
7. Save topics + briefing

Key changes:
- No merge pass (it was causing OOM and complexity — save for v3)
- Narrative prompt per group (not per app)
- Briefing generated from narratives
- Max 8 LLM calls per batch to avoid OOM
- Groups with 1 notification: headline = "{title}: {content.take(60)}" — no LLM needed

Store briefing text via a special TopicEntity with `headline = "BRIEFING"` and `summary` = the briefing text. The UI queries this separately.

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: rewrite TopicEngine with narrative generation and daily briefing"
```

---

## Task 3: Digest UI — Briefing + Plain Story Cards

**Files to modify:**
- `app/src/main/java/com/focal/ui/digest/DigestViewModel.kt`
- `app/src/main/java/com/focal/ui/digest/DigestScreen.kt`
- `app/src/main/java/com/focal/ui/digest/TopicCard.kt`
- `app/src/main/java/com/focal/ui/all/AllNotificationsViewModel.kt`
- `app/src/main/java/com/focal/ui/all/AllNotificationsScreen.kt`
- `app/src/main/java/com/focal/ui/theme/Color.kt`

- [ ] **Step 1: Rewrite TopicCard — plain text, no badges**

Remove all category badge logic, color coding, and label text. The card becomes:
- Headline text (the LLM narrative sentence) — bold
- Source app pills below
- Clickable

```kotlin
@Composable
fun TopicCard(
    topic: TopicEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Parse sourceApps JSON
    val sourceApps = try {
        val arr = JSONArray(topic.sourceApps)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = topic.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (sourceApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sourceApps.forEach { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = app,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite DigestViewModel**

Serve topics + briefing text. Query topics where headline != "BRIEFING" for story cards, and the BRIEFING topic separately for the top paragraph.

- [ ] **Step 3: Rewrite DigestScreen**

Layout:
1. "Your Digest" header
2. Briefing paragraph (if available)
3. Story cards (plain, no badges)
4. Noise count footer
5. Pull-to-refresh

- [ ] **Step 4: Update All page — 2 sections**

`AllNotificationsViewModel`: 2 flows — `matters` and `noise`.
`AllNotificationsScreen`: 2 CategorySections — "Matters" (expanded) and "Noise" (collapsed).

- [ ] **Step 5: Clean up unused colors**

Remove `ActionableAmber`, `ActionableAmberContainer`, `DigestBlue`, `DigestBlueContainer` from Color.kt if no longer used.

- [ ] **Step 6: Verify build and tests**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: narrative Digest UI with briefing paragraph and plain story cards"
```

---

## Task 4: On-Device Test

- [ ] **Step 1: Install, re-enable listener, clear data for fresh start**
- [ ] **Step 2: Wait for notifications, verify auto worker trigger at 30s**
- [ ] **Step 3: Check Digest — briefing paragraph should appear at top, story cards below**
- [ ] **Step 4: Check All — 2 sections (matters/noise)**
- [ ] **Step 5: Verify no "Here's a summary:." anywhere**
- [ ] **Step 6: Commit and push**

---

## Summary

4 tasks:
1. **Classification + DB migration** — 2-class system, binary prompts, simplified rules
2. **TopicEngine rewrite** — narrative generation per story group + daily briefing
3. **Digest UI** — briefing paragraph + plain story cards + All page simplified
4. **On-device test** — verify the full pipeline produces readable narratives
