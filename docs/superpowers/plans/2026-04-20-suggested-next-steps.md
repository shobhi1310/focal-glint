# Suggested Next Steps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LLM generates up to 3 contextual action suggestions per topic (e.g., "Call Mom back — she tried twice → Phone"), rendered as tappable buttons in the topic detail screen that open the relevant app.

**Architecture:** Extend the existing `buildTopicPrompt` to also produce ACTIONS. Parse title + summary + actions in one pass. Store actions as JSON in TopicEntity. Resolve action type + packageName to Android Intents at tap time. Tool calling on Gemma 4 E2B, few-shot fallback on Gemma 3 1B.

**Tech Stack:** LiteRT-LM, Jetpack Compose, Room (migration v5→v6), Android Intent system

---

## Task 1: Data Model + DB Migration

**Files to create:**
- `app/src/main/java/com/focal/intelligence/SuggestedAction.kt`

**Files to modify:**
- `app/src/main/java/com/focal/data/db/entity/TopicEntity.kt`
- `app/src/main/java/com/focal/data/db/FocalDatabase.kt`
- `app/src/main/java/com/focal/di/DatabaseModule.kt`

- [ ] **Step 1: Create SuggestedAction data class with JSON helpers**

Create `app/src/main/java/com/focal/intelligence/SuggestedAction.kt`:
```kotlin
package com.focal.intelligence

import org.json.JSONArray
import org.json.JSONObject

data class SuggestedAction(
    val label: String,
    val type: String,
    val app: String,
    val packageName: String = ""
) {
    companion object {
        val VALID_TYPES = setOf("call", "reply", "open_app", "view", "pay", "track")

        fun fromJson(json: JSONObject): SuggestedAction {
            return SuggestedAction(
                label = json.optString("label", ""),
                type = json.optString("type", "open_app").let {
                    if (it in VALID_TYPES) it else "open_app"
                },
                app = json.optString("app", ""),
                packageName = json.optString("packageName", "")
            )
        }

        fun listFromJson(jsonStr: String?): List<SuggestedAction> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }

        fun listToJson(actions: List<SuggestedAction>): String {
            val arr = JSONArray()
            actions.forEach { action ->
                arr.put(JSONObject().apply {
                    put("label", action.label)
                    put("type", action.type)
                    put("app", action.app)
                    put("packageName", action.packageName)
                })
            }
            return arr.toString()
        }
    }
}
```

- [ ] **Step 2: Add suggestedActions column to TopicEntity**

In `app/src/main/java/com/focal/data/db/entity/TopicEntity.kt`, add after `needsNarrativeRegen`:
```kotlin
    @ColumnInfo(name = "suggested_actions")
    val suggestedActions: String? = null
```

- [ ] **Step 3: Add MIGRATION_5_6 to FocalDatabase**

In `app/src/main/java/com/focal/data/db/FocalDatabase.kt`:

Bump `version = 6` in the `@Database` annotation.

Add in companion object:
```kotlin
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topics ADD COLUMN suggested_actions TEXT")
            }
        }
```

- [ ] **Step 4: Register migration in DatabaseModule**

In `DatabaseModule.kt`, add `FocalDatabase.MIGRATION_5_6` to `.addMigrations(...)`.

- [ ] **Step 5: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add SuggestedAction model and suggested_actions column (migration v5→v6)"
```

---

## Task 2: Prompt + Parser Updates

**Files to modify:**
- `app/src/main/java/com/focal/intelligence/PromptBuilder.kt`
- `app/src/main/java/com/focal/intelligence/LlmResponseParser.kt`

**Files to create:**
- `app/src/test/java/com/focal/intelligence/SuggestedActionParserTest.kt`

- [ ] **Step 1: Write parser tests**

Create `app/src/test/java/com/focal/intelligence/SuggestedActionParserTest.kt`:
```kotlin
package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestedActionParserTest {

    @Test
    fun `parses complete topic with actions`() {
        val raw = """
            TITLE: Mom wants Sunday lunch
            SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.
            ACTIONS:
            - Call Mom back — she tried twice | call | Phone
            - Reply about Sunday plans | reply | WhatsApp
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("Mom wants Sunday lunch", result.title)
        assertEquals("Two missed calls and a WhatsApp asking if you're bringing Maya.", result.summary)
        assertEquals(2, result.actions.size)
        assertEquals("Call Mom back — she tried twice", result.actions[0].label)
        assertEquals("call", result.actions[0].type)
        assertEquals("Phone", result.actions[0].app)
        assertEquals("reply", result.actions[1].type)
    }

    @Test
    fun `parses topic without actions section`() {
        val raw = """
            TITLE: ICICI card charge
            SUMMARY: Rs 44,000 spent at Amazon.
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("ICICI card charge", result.title)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `invalid action type defaults to open_app`() {
        val raw = """
            TITLE: Test
            SUMMARY: Test summary.
            ACTIONS:
            - Do something | unknown_type | SomeApp
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("open_app", result.actions[0].type)
    }

    @Test
    fun `caps at 3 actions`() {
        val raw = """
            TITLE: Test
            SUMMARY: Test summary.
            ACTIONS:
            - Action one | call | Phone
            - Action two | reply | WhatsApp
            - Action three | open_app | Gmail
            - Action four | view | Chrome
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals(3, result.actions.size)
    }

    @Test
    fun `SuggestedAction JSON round-trip`() {
        val actions = listOf(
            SuggestedAction("Call Mom", "call", "Phone", "com.android.phone"),
            SuggestedAction("Reply", "reply", "WhatsApp", "com.whatsapp")
        )
        val json = SuggestedAction.listToJson(actions)
        val restored = SuggestedAction.listFromJson(json)
        assertEquals(2, restored.size)
        assertEquals("Call Mom", restored[0].label)
        assertEquals("com.android.phone", restored[0].packageName)
    }

    @Test
    fun `listFromJson handles null and empty`() {
        assertTrue(SuggestedAction.listFromJson(null).isEmpty())
        assertTrue(SuggestedAction.listFromJson("").isEmpty())
        assertTrue(SuggestedAction.listFromJson("invalid").isEmpty())
    }
}
```

- [ ] **Step 2: Update TopicContent and parseTopicContent**

In `app/src/main/java/com/focal/intelligence/LlmResponseParser.kt`:

Replace the `TopicContent` data class:
```kotlin
    data class TopicContent(
        val title: String,
        val summary: String,
        val actions: List<SuggestedAction> = emptyList()
    )
```

Replace `parseTopicContent`:
```kotlin
    fun parseTopicContent(raw: String): TopicContent? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val titleMatch = Regex("(?:TITLE:\\s*)(.+)", RegexOption.IGNORE_CASE).find(trimmed)
        val summaryMatch = Regex("(?:SUMMARY:\\s*)(.+)", RegexOption.IGNORE_CASE).find(trimmed)

        val title: String
        val summary: String

        if (titleMatch != null && summaryMatch != null) {
            title = titleMatch.groupValues[1].trim().take(60)
            summary = summaryMatch.groupValues[1].trim().take(300)
        } else {
            val lines = trimmed.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            title = lines.first().removePrefix("TITLE:").trim().take(60)
            summary = if (lines.size > 1) {
                lines.drop(1).first().removePrefix("SUMMARY:").trim().take(300)
            } else title
        }

        // Parse ACTIONS section
        val actionsStart = trimmed.indexOf("ACTIONS:", ignoreCase = true)
        val actions = if (actionsStart >= 0) {
            val actionsBlock = trimmed.substring(actionsStart + "ACTIONS:".length)
            actionsBlock.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .take(3)
                .mapNotNull { line ->
                    val parts = line.removePrefix("- ").split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val type = parts[1].lowercase().let {
                            if (it in SuggestedAction.VALID_TYPES) it else "open_app"
                        }
                        SuggestedAction(label = parts[0], type = type, app = parts[2])
                    } else null
                }
        } else emptyList()

        return TopicContent(title = title, summary = summary, actions = actions)
    }
```

- [ ] **Step 3: Extend buildTopicPrompt with ACTIONS**

In `app/src/main/java/com/focal/intelligence/PromptBuilder.kt`, replace `buildTopicPrompt`:
```kotlin
    fun buildTopicPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("You are generating a topic card for a notification digest app.")
        sb.appendLine("Given these notifications, produce:")
        sb.appendLine("1. TITLE: A short, action-invoking headline (3-5 words max).")
        sb.appendLine("2. SUMMARY: One sentence explaining what happened. Be specific — names, amounts, times.")
        sb.appendLine("3. ACTIONS: Up to 3 suggested next steps for the user.")
        sb.appendLine("   Format each action as: {contextual label} | {type} | {app name}")
        sb.appendLine("   Types: call, reply, open_app, view, pay, track")
        sb.appendLine("   Labels should give direction — explain WHY, not just what.")
        sb.appendLine()
        sb.appendLine("Examples:")
        sb.appendLine()
        sb.appendLine("TITLE: Mom wants Sunday lunch")
        sb.appendLine("SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Call Mom back — she tried twice | call | Phone")
        sb.appendLine("- Reply about Sunday plans | reply | WhatsApp")
        sb.appendLine()
        sb.appendLine("TITLE: ₹44K ICICI card charge")
        sb.appendLine("SUMMARY: Rs 44,000 spent on your ICICI card at Amazon on Apr 18.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Check if this charge was you | open_app | Messages")
        sb.appendLine("- Review your ICICI card statement | view | Gmail")
        sb.appendLine()
        sb.appendLine("TITLE: Swiggy order arriving")
        sb.appendLine("SUMMARY: Your Swiggy order from Biryani Blues is out for delivery.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Track your delivery | track | Swiggy")
        sb.appendLine()
        sb.appendLine("Notifications:")
        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.appName} — ${notif.title}: ${content.take(150)}")
        }
        sb.appendLine()
        sb.appendLine("TITLE:")
        return sb.toString()
    }
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.focal.intelligence.SuggestedActionParserTest"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: extend topic prompt with ACTIONS, add parser with tests"
```

---

## Task 3: TopicEngine Integration

**Files to modify:**
- `app/src/main/java/com/focal/intelligence/TopicEngine.kt`
- `app/src/main/java/com/focal/data/repository/TopicRepository.kt`
- `app/src/main/java/com/focal/data/db/dao/TopicDao.kt`

- [ ] **Step 1: Add updateTopicActions to TopicDao and TopicRepository**

In `TopicDao.kt`, add:
```kotlin
    @Query("UPDATE topics SET suggested_actions = :actionsJson, updated_at = :timestamp WHERE id = :topicId")
    suspend fun updateActions(topicId: String, actionsJson: String, timestamp: Long)
```

In `TopicRepository.kt`, add:
```kotlin
    suspend fun updateTopicActions(topicId: String, actionsJson: String) {
        topicDao.updateActions(topicId, actionsJson, System.currentTimeMillis())
    }
```

- [ ] **Step 2: Update TopicEngine to parse and store actions**

In `TopicEngine.kt`, update `regenerateNarratives`. The current code parses `TopicContent` for title and summary. Now it also handles actions.

In the multi-notification LLM block, after parsing:
```kotlin
                headline = try {
                    val prompt = PromptBuilder.buildTopicPrompt(members)
                    val raw = inferenceProvider.generate(prompt, maxTokens = 200)
                    llmCallCount++
                    val parsed = LlmResponseParser.parseTopicContent(raw)
                    if (parsed != null) {
                        isLlmGenerated = true
                        summary = parsed.summary
                        actions = resolveActionPackages(parsed.actions, members)
                        parsed.title
                    } else {
                        "${members.first().appName} · ${members.size} messages"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Topic generation failed for topic ${topic.id}", e)
                    "${members.first().appName} · ${members.size} messages"
                }
```

Declare `var actions: List<SuggestedAction> = emptyList()` alongside the existing `var summary` and `var headline` at the top of the loop.

For single-notification topics, generate a default action:
```kotlin
            if (members.size == 1) {
                val m = members.first()
                headline = m.title.take(40)
                summary = (m.bigText ?: m.content).take(300)
                isLlmGenerated = true
                actions = listOf(SuggestedAction(
                    label = "Open in ${m.appName}",
                    type = "open_app",
                    app = m.appName,
                    packageName = m.packageName
                ))
            }
```

After updating headline/summary, also store actions:
```kotlin
            if (actions.isNotEmpty()) {
                topicRepository.updateTopicActions(topic.id, SuggestedAction.listToJson(actions))
            }
```

Add the package name resolver as a private method:
```kotlin
    private fun resolveActionPackages(
        actions: List<SuggestedAction>,
        members: List<NotificationEntity>
    ): List<SuggestedAction> {
        val knownApps = mapOf(
            "phone" to "com.android.phone",
            "messages" to "com.google.android.apps.messaging",
            "chrome" to "com.android.chrome"
        )
        return actions.map { action ->
            val packageName = members.firstOrNull {
                it.appName.equals(action.app, ignoreCase = true)
            }?.packageName
                ?: knownApps[action.app.lowercase()]
                ?: ""
            action.copy(packageName = packageName)
        }
    }
```

Also increase `maxTokens` from 150 to 200 in the generate call to accommodate the ACTIONS section.

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: TopicEngine generates and stores suggested actions per topic"
```

---

## Task 4: ActionIntentResolver

**Files to create:**
- `app/src/main/java/com/focal/intelligence/ActionIntentResolver.kt`

- [ ] **Step 1: Create ActionIntentResolver**

Create `app/src/main/java/com/focal/intelligence/ActionIntentResolver.kt`:
```kotlin
package com.focal.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.focal.data.db.entity.NotificationEntity

object ActionIntentResolver {

    fun resolve(context: Context, action: SuggestedAction, notifications: List<NotificationEntity> = emptyList()): Intent? {
        return when (action.type) {
            "call" -> {
                val number = extractPhoneNumber(notifications)
                if (number != null) Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                else launchApp(context, action.packageName)
            }
            "view" -> {
                val url = extractUrl(notifications)
                if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url))
                else launchApp(context, action.packageName)
            }
            else -> launchApp(context, action.packageName)
        }
    }

    private fun launchApp(context: Context, packageName: String): Intent? {
        if (packageName.isBlank()) return null
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    private fun extractPhoneNumber(notifications: List<NotificationEntity>): String? {
        val allText = notifications.joinToString(" ") { "${it.title} ${it.content} ${it.bigText ?: ""}" }
        val phoneRegex = Regex("(?:\\+91|0)?[6-9]\\d{9}")
        return phoneRegex.find(allText)?.value?.let {
            if (it.startsWith("+")) it else "+91$it"
        }
    }

    private fun extractUrl(notifications: List<NotificationEntity>): String? {
        val allText = notifications.joinToString(" ") { "${it.title} ${it.content} ${it.bigText ?: ""}" }
        val urlRegex = Regex("https?://\\S+")
        return urlRegex.find(allText)?.value
    }
}
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add ActionIntentResolver for mapping actions to Android intents"
```

---

## Task 5: Topic Detail UI — Suggested Next Steps Section

**Files to modify:**
- `app/src/main/java/com/focal/ui/digest/TopicDetailScreen.kt`
- `app/src/main/java/com/focal/ui/digest/TopicDetailViewModel.kt`

- [ ] **Step 1: Expose actions in TopicDetailViewModel**

In `TopicDetailViewModel.kt`, update `TopicDetailUiState` to include parsed actions:
```kotlin
data class TopicDetailUiState(
    val topic: TopicEntity? = null,
    val notifications: List<NotificationEntity> = emptyList(),
    val actions: List<SuggestedAction> = emptyList(),
    val isLoading: Boolean = true
)
```

In the `loadTopic()` function, after loading the topic, parse the actions:
```kotlin
            val actions = SuggestedAction.listFromJson(topic?.suggestedActions)
```

And include in the state update:
```kotlin
            _uiState.value = TopicDetailUiState(
                topic = topic,
                notifications = notifications,
                actions = actions,
                isLoading = false
            )
```

Add import: `import com.focal.intelligence.SuggestedAction`

- [ ] **Step 2: Add SuggestedNextSteps section to TopicDetailScreen**

In `TopicDetailScreen.kt`, add between the Summary card and the Sources section:

Add a private composable:
```kotlin
@Composable
private fun SuggestedNextStepsSection(
    actions: List<SuggestedAction>,
    notifications: List<NotificationEntity>
) {
    if (actions.isEmpty()) return
    val context = LocalContext.current

    SectionHeader(title = "SUGGESTED NEXT STEPS")
    Spacer(modifier = Modifier.height(8.dp))

    actions.forEachIndexed { index, action ->
        val isPrimary = index == 0
        Surface(
            color = if (isPrimary) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = if (!isPrimary) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    val intent = ActionIntentResolver.resolve(context, action, notifications)
                    if (intent != null) {
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isPrimary) MaterialTheme.colorScheme.background
                            else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "→ ${action.app.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPrimary) MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}
```

Call it from the main composable, passing `state.actions` and `state.notifications`.

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import com.focal.intelligence.ActionIntentResolver
import com.focal.intelligence.SuggestedAction
```

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: render Suggested Next Steps in topic detail with intent launching"
```

---

## Task 6: Tests + On-Device Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew testDebugUnitTest
```

- [ ] **Step 2: Install and verify**

```bash
./gradlew installDebug
```

- Trigger a refresh on the Today page
- Wait for worker to complete (check logs for TopicEngine)
- Tap a story card → verify "SUGGESTED NEXT STEPS" section appears
- Tap an action → verify it opens the correct app
- Verify first action has dark/filled style, subsequent ones have outline style

- [ ] **Step 3: Commit and push**

```bash
git push
```

---

## Summary

6 tasks:
1. **Data model + migration** — SuggestedAction class, TopicEntity column, DB v5→v6
2. **Prompt + parser** — Extended buildTopicPrompt with ACTIONS, parseTopicContent handles 3 outputs, tests
3. **TopicEngine** — Parse actions, resolve package names, store in DB
4. **ActionIntentResolver** — Maps action type + package to Android Intent
5. **Topic Detail UI** — Suggested Next Steps section with primary/secondary button styles
6. **Tests + verification** — Full test suite + on-device check
