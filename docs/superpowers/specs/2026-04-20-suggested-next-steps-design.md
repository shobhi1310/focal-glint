# Suggested Next Steps — Per-Topic Actionable Intents

**Goal:** The LLM generates up to 3 contextual, human-friendly action suggestions per topic. Tapping an action opens the relevant app. Labels give direction, not just commands ("Call Mom back — she tried twice" not "Call Mom").

---

## 1. Architecture

The existing `buildTopicPrompt` is extended to generate TITLE + SUMMARY + ACTIONS in one LLM call. Each action has three parts:

- **Label** — Contextual, conversational suggestion (e.g., "Reply to Darahas about the Claude post")
- **Action type** — One of 6 fixed types: `call`, `reply`, `open_app`, `view`, `pay`, `track`
- **App name** — The target app (e.g., "WhatsApp", "Phone", "CRED")

Actions are stored as JSON in `TopicEntity.suggestedActions`. At render time, tapping an action fires an Android Intent resolved from the action type + package name.

Two LLM paths:
- **Gemma 4 E2B** — Tool calling via `generateWithTools()` for guaranteed structured output
- **Gemma 3 1B** — Few-shot prompting with `|`-delimited format, parsed with regex. Falls back to a single "Open {primaryApp}" action on parse failure.

## 2. Action Types

| Type | Android Intent | Special Handling |
|------|---------------|------------------|
| `call` | `ACTION_DIAL` with `tel:` URI | Pre-fills phone number if detected in notification content |
| `reply` | `getLaunchIntentForPackage` | Opens messaging app (WhatsApp, Telegram, Slack, etc.) |
| `open_app` | `getLaunchIntentForPackage` | Generic app launch |
| `view` | `ACTION_VIEW` with URL | Opens URL in browser or app if URL detected, else app launch |
| `pay` | `getLaunchIntentForPackage` | Opens payment app (CRED, Paytm, PhonePe, etc.) |
| `track` | `getLaunchIntentForPackage` | Opens logistics app (Swiggy, Zomato, Amazon, etc.) |

Most types resolve to a simple app launch. `call` and `view` get special handling when a phone number or URL is found in the notification content.

## 3. Prompt Design

### Few-shot format (Gemma 3 1B)

The existing `buildTopicPrompt` is extended:

```
You are generating a topic card for a notification digest app.
Given these notifications, produce:
1. TITLE: A short, action-invoking headline (3-5 words max).
2. SUMMARY: One sentence explaining what happened. Be specific — names, amounts, times.
3. ACTIONS: Up to 3 suggested next steps. Each on its own line.
   Format: {contextual label} | {type} | {app name}
   Types: call, reply, open_app, view, pay, track
   Labels should give direction — explain WHY the user should act, not just what to do.

Examples:

TITLE: Mom wants Sunday lunch
SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.
ACTIONS:
- Call Mom back — she tried twice | call | Phone
- Reply about Sunday plans | reply | WhatsApp

TITLE: ₹44K ICICI card charge
SUMMARY: Rs 44,000 spent on your ICICI card at Amazon on Apr 18.
ACTIONS:
- Check if this charge was you | open_app | Messages
- Review your ICICI card statement | view | Gmail

TITLE: Swiggy order arriving
SUMMARY: Your Swiggy order from Biryani Blues is out for delivery.
ACTIONS:
- Track your delivery | track | Swiggy
- Keep your phone handy for the rider | open_app | Phone

Notifications:
[1] WhatsApp — Mom: are you and Maya coming sunday?
[2] Phone — Mom: 2 missed calls

TITLE:
```

### Tool calling schema (Gemma 4 E2B)

```kotlin
val topicToolSchema = ToolSet(
    name = "generate_topic",
    description = "Generate a topic card with title, summary, and suggested actions",
    parameters = mapOf(
        "title" to "string: short 3-5 word headline",
        "summary" to "string: one sentence summary",
        "actions" to "array of objects with: label (string), type (enum: call/reply/open_app/view/pay/track), app (string)"
    )
)
```

When `generateWithTools` is available and the model supports it, use this path. The response is already structured JSON from the tool call — no parsing needed.

## 4. Parsing

### Few-shot response parser

```kotlin
data class SuggestedAction(
    val label: String,
    val type: String,
    val app: String,
    val packageName: String = ""
)

data class TopicContent(
    val title: String,
    val summary: String,
    val actions: List<SuggestedAction> = emptyList()
)
```

Update `parseTopicContent` to also extract ACTIONS lines:

1. Find lines after "ACTIONS:" marker
2. Each line starting with "- " is split on " | " into 3 parts
3. Validate action type is one of the 6 allowed types (default to `open_app` if unknown)
4. Take first 3 valid actions max
5. If no ACTIONS section found or all lines fail to parse, return empty list (fallback to "Open {primaryApp}")

### Package name resolution

After parsing, resolve each action's `app` name to a `packageName` by matching against the topic's source notifications:

```kotlin
fun resolvePackageName(appName: String, notifications: List<NotificationEntity>): String {
    return notifications.firstOrNull {
        it.appName.equals(appName, ignoreCase = true)
    }?.packageName ?: ""
}
```

For "Phone", hardcode `com.android.phone`. For common apps not in notifications, maintain a small lookup map.

## 5. Storage

### TopicEntity — new column

```kotlin
@ColumnInfo(name = "suggested_actions")
val suggestedActions: String? = null  // JSON array
```

JSON format:
```json
[
  {"label": "Call Mom back — she tried twice", "type": "call", "app": "Phone", "packageName": "com.android.phone"},
  {"label": "Reply about Sunday plans", "type": "reply", "app": "WhatsApp", "packageName": "com.whatsapp"}
]
```

### DB migration

Next migration (v5→v6 or whatever the current version is):
```sql
ALTER TABLE topics ADD COLUMN suggested_actions TEXT
```

## 6. Intent Resolution

`ActionIntentResolver` — a utility that maps SuggestedAction to an Android Intent:

```kotlin
object ActionIntentResolver {
    fun resolve(context: Context, action: SuggestedAction, notifications: List<NotificationEntity>): Intent? {
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
        return phoneRegex.find(allText)?.value
    }

    private fun extractUrl(notifications: List<NotificationEntity>): String? {
        val allText = notifications.joinToString(" ") { "${it.title} ${it.content} ${it.bigText ?: ""}" }
        val urlRegex = Regex("https?://\\S+")
        return urlRegex.find(allText)?.value
    }
}
```

## 7. UI — Topic Detail Screen

Between the Summary card and the Sources section, add:

```
SUGGESTED NEXT STEPS          ← SectionHeader

[████████████████████████████████████████████]  ← Primary action (filled dark)
  Call Mom back — she tried twice    → PHONE

[                                             ]  ← Secondary action (outline)
  Reply about Sunday plans        → WHATSAPP

[                                             ]  ← Secondary action (outline)
  Check the ₹553 charge           → MESSAGES
```

- First action: filled Surface with dark background, white text. Most prominent.
- Remaining actions: outline Surface with border, dark text. Secondary.
- Right side shows app name in uppercase.
- Tapping fires `ActionIntentResolver.resolve()` via `context.startActivity()`.
- If no actions exist for a topic, the entire section is hidden.

## 8. TopicEngine Changes

In `regenerateNarratives`, after the LLM generates title + summary + actions:

1. Parse all three from the response
2. Resolve package names for each action
3. Serialize actions to JSON
4. Store in `topicRepository.updateTopicActions(topicId, actionsJson)`

For single-notification topics (no LLM call), generate a default action:
```kotlin
val defaultAction = SuggestedAction(
    label = "Open in ${notif.appName}",
    type = "open_app",
    app = notif.appName,
    packageName = notif.packageName
)
```

## 9. Files to Change

| File | Change |
|------|--------|
| `PromptBuilder.kt` | Extend `buildTopicPrompt` with ACTIONS section + few-shot examples |
| `LlmResponseParser.kt` | Update `TopicContent` data class + `parseTopicContent` to extract actions |
| `TopicEngine.kt` | Parse actions, resolve package names, store in topic |
| `TopicEntity.kt` | Add `suggestedActions` column |
| `TopicDao.kt` | Add update method for actions |
| `TopicRepository.kt` | Add `updateTopicActions` method |
| `FocalDatabase.kt` | Migration adding `suggested_actions` column |
| `DatabaseModule.kt` | Register migration |
| `ActionIntentResolver.kt` | New — maps action type + package to Android Intent |
| `SuggestedAction.kt` | New — data class + JSON serialization helpers |
| `TopicDetailScreen.kt` | Add Suggested Next Steps section between Summary and Sources |
| `TopicDetailViewModel.kt` | Parse and expose actions from topic |
