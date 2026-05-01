# Cloud Inference Design

Add a cloud classification path alongside on-device inference. A fine-tuned Gemma 4 model hosted via llama.cpp on Focal's infrastructure handles classification + extraction tool calls when enabled. On-device Gemma 4 E2B remains the default. Embedding-based topic clustering and narrative generation always stay on-device.

## Context

Gemma 3 1B is being removed as a model option (minimum hardware requirements raised). On-device Gemma 4 E2B works but the fine-tuned cloud model provides better classification accuracy and reliable multi-tool calling (the on-device model struggled with 4+ tools, producing text instead of tool calls).

## Architecture

### Routing split

| Operation | Cloud ON | Cloud OFF |
|-----------|----------|-----------|
| Classification (matters/noise) | Cloud (fine-tuned Gemma 4) | On-device (Gemma 4 E2B) |
| Extraction (widget tool calls) | Cloud (fine-tuned Gemma 4) | On-device (Gemma 4 E2B) |
| Embedding (vector clustering) | Always on-device | Always on-device |
| Narrative (title/summary/actions) | Always on-device | Always on-device |

### Flow

```
ClassificationWorker
    └── Classifier
            ├── classifyBatch()
            │       ├── cloudEnabled → CloudClassifier.classifyBatch() (HTTP)
            │       └── !cloudEnabled → inferenceProvider.generateWithTools() (local)
            ├── classifyAndExtractBatch()
            │       ├── cloudEnabled → CloudClassifier.classifyAndExtractBatch() (HTTP)
            │       └── !cloudEnabled → inferenceProvider.generateWithTools() (local)
            │
    └── TopicNarrativeProcessor
            └── always inferenceProvider.generate() (local)
```

The routing decision happens inside `Classifier` based on `ModelManager.isCloudEnabled()`. `ClassificationWorker` doesn't change — it calls the same Classifier methods.

## Cloud API Contract

### Server

llama.cpp's built-in OpenAI-compatible HTTP server at a fixed endpoint (hardcoded in app, managed by Focal's infrastructure team).

### Classification request

```
POST https://<ENDPOINT>/v1/chat/completions
Content-Type: application/json
X-Focal-Consent: true|false
```

```json
{
  "model": "gemma-4-finetuned",
  "messages": [
    {"role": "system", "content": "<system prompt>"},
    {"role": "user", "content": "<batch notification prompt>"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "classifyNotification",
        "description": "Classify a notification from the batch by its index",
        "parameters": {
          "type": "object",
          "properties": {
            "index": {"type": "integer", "description": "1-based index of the notification"},
            "category": {"type": "string", "enum": ["matters", "noise"]},
            "reason": {"type": "string", "description": "Short reason for classification"}
          },
          "required": ["index", "category", "reason"]
        }
      }
    }
  ],
  "tool_choice": "auto",
  "max_tokens": 512,
  "temperature": 0.1
}
```

### Classification + extraction request

Same structure with additional tools in the `tools` array:

```json
{
  "tools": [
    {"type": "function", "function": {"name": "classifyNotification", ...}},
    {"type": "function", "function": {"name": "extractFinance", "parameters": {
      "properties": {
        "index": {"type": "integer"},
        "amount": {"type": "number"},
        "merchant": {"type": "string"},
        "category": {"type": "string", "enum": ["food", "transport", "shopping", "bills", "transfer", "other"]},
        "direction": {"type": "string", "enum": ["debit", "credit"]}
      }, "required": ["index", "amount", "merchant", "category", "direction"]
    }}},
    {"type": "function", "function": {"name": "extractWork", "parameters": {
      "properties": {
        "index": {"type": "integer"},
        "entity": {"type": "string"},
        "sender": {"type": "string"},
        "action": {"type": "string", "enum": ["review_requested", "merged", "commented", "assigned", "mentioned", "other"]},
        "repo": {"type": "string"}
      }, "required": ["index", "entity", "sender", "action", "repo"]
    }}},
    {"type": "function", "function": {"name": "extractPersonal", "parameters": {
      "properties": {
        "index": {"type": "integer"},
        "sender": {"type": "string"},
        "channel": {"type": "string", "enum": ["call", "message", "email", "other"]},
        "count": {"type": "integer"},
        "snippet": {"type": "string"}
      }, "required": ["index", "sender", "channel", "count", "snippet"]
    }}},
    {"type": "function", "function": {"name": "extractLogistics", "parameters": {
      "properties": {
        "index": {"type": "integer"},
        "item": {"type": "string"},
        "merchant": {"type": "string"},
        "status": {"type": "string", "enum": ["ordered", "shipped", "out_for_delivery", "delivered", "cancelled"]},
        "etaMinutes": {"type": "integer"}
      }, "required": ["index", "item", "merchant", "status", "etaMinutes"]
    }}}
  ]
}
```

System prompt for extraction requests appends:
```
After classifying each notification, if it is 'matters', also call the appropriate extraction tool(s) for it. A notification can match multiple extraction tools (e.g., a food delivery payment is both finance and logistics). Available extraction categories: finance, work, personal, logistics.
```

### Response format

llama.cpp returns standard OpenAI format:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "tool_calls": [
        {
          "id": "call_1",
          "type": "function",
          "function": {
            "name": "classifyNotification",
            "arguments": "{\"index\":1,\"category\":\"matters\",\"reason\":\"authentication request from work SSO\"}"
          }
        },
        {
          "id": "call_2",
          "type": "function",
          "function": {
            "name": "extractWork",
            "arguments": "{\"index\":1,\"entity\":\"Forma SSO\",\"sender\":\"Okta\",\"action\":\"other\",\"repo\":\"\"}"
          }
        }
      ]
    }
  }]
}
```

### Headers

- `Content-Type: application/json`
- `X-Focal-Consent: true|false` — signals whether server may retain request data for training
- No auth token for v1 (private infrastructure, not public API)

### Error handling

- Network failure / timeout (30s) → classification stays `pending`, retried on next ClassificationWorker cycle
- HTTP 4xx/5xx → same retry behavior
- No offline queue — if cloud unreachable, notifications wait

## Data Privacy

- Notification text sent to cloud: packageName, appName, title, content (first 200 chars). Same data as shown in prompts today.
- No Room DB data sent. No embeddings sent. No user identity sent.
- `X-Focal-Consent: false` (default) → server processes and discards request immediately
- `X-Focal-Consent: true` → server may retain anonymized request data for model training
- Both toggles (cloud + consent) are explicit user actions in the Tune screen

## Settings UI

New INFERENCE section in TuneScreen after APPEARANCE:

- **Cloud classification** toggle (Switch) — "Use our hosted model for better notification classification."
- **Help improve Focal** toggle (Switch, only visible when cloud is ON) — "Allow anonymized notification data to be used for training."
- Both persist to SharedPreferences via ModelManager
- Take effect immediately on next ClassificationWorker cycle

## Model Changes

- Remove `GEMMA3_1B` from `ModelVariant` enum (minimum hardware raised)
- `GEMMA4_E2B` becomes the only on-device option
- SetupScreen model selection simplified (single model, download + start)
- If Gemma 3 1B file exists on disk from previous installs, leave it (no active cleanup — user can clear app data if they want space back)

## File Structure

### New files

```
intelligence/CloudClassifier.kt          — HTTP client for llama.cpp endpoint
intelligence/CloudApiModels.kt           — Request/response data classes (OpenAI format)
```

### Modified files

```
intelligence/ModelManager.kt             — Remove GEMMA3_1B, add cloud/consent preference methods
intelligence/Classifier.kt              — Inject CloudClassifier, route based on isCloudEnabled()
di/IntelligenceModule.kt                — Provide CloudClassifier
ui/tune/TuneScreen.kt                   — Add INFERENCE section with toggles
ui/tune/TuneViewModel.kt                — Add cloud/consent state + toggle handlers
ui/setup/SetupScreen.kt                 — Remove Gemma 3 1B from model selection
app/build.gradle.kts                    — Add OkHttp dependency (if not already present)
```

### Unchanged files

```
intelligence/LiteRtLmProvider.kt         — Still used for narrative gen + cloud-off classification
intelligence/TopicNarrativeProcessor.kt  — Always local
intelligence/TopicEngine.kt              — Always local (embeddings)
intelligence/PromptBuilder.kt            — Same prompts, reused by CloudClassifier
intelligence/ExtractionToolSet.kt        — Data classes reused, tool classes only used locally
worker/ClassificationWorker.kt           — Calls same Classifier methods (routing is internal)
```

## Endpoint Configuration

Hardcoded in `CloudClassifier` as a companion object constant:

```kotlin
companion object {
    const val BASE_URL = "https://inference.focal.app"
    const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    const val MODEL_NAME = "gemma-4-finetuned"
    const val TIMEOUT_SECONDS = 30L
}
```

Updated via app release when infrastructure changes. Not user-configurable.
