# Focal

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-34A853?style=for-the-badge&logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Min%20SDK-31-34A853?style=for-the-badge&logo=android" alt="Min SDK 31">
  <img src="https://img.shields.io/badge/LiteRT-2.1.4-FF6F00?style=for-the-badge" alt="LiteRT">
  <img src="https://img.shields.io/badge/LiteRT--LM-0.11.0-FF6F00?style=for-the-badge" alt="LiteRT-LM">
</p>

Focal is an on-device Android app that reads your notifications, classifies what actually matters, clusters them into topics, and gives you a clean daily digest. All running locally with Gemma models via LiteRT-LM. No data leaves your phone.

<p align="center">
  <em>Notifications &rarr; Classify &rarr; Topics &rarr; Summaries &rarr; Widgets</em>
</p>

---

## What it does

- **Smart classification**. Every incoming notification is triaged as **Matters**, **Noise**, or **Auto** using an on-device LLM with tool use
- **Topic clustering**. "Matters" notifications are semantically grouped into topics via embeddings, with channel-aware grouping for conversations
- **AI-generated summaries**. Each topic gets a concise narrative summary written by the model
- **Structured extraction**. Automatically pulls out finance, work, personal, and logistics data from notifications into queryable widgets
- **Custom widgets (Pulse)**. Build widgets that ask your day questions like "How much did I spend?" or "What deliveries are arriving?"
- **Per-app tuning**. Set any app to Matters, Auto, or Noise with a single tap
- **Rules engine**. Keyword, sender, and app-level rules override the model when you need precision
- **Bank SMS detection**. Recognizes Indian bank transaction SMS and extracts structured transaction data
- **Privacy-first**. All processing runs locally; no account, no cloud, no telemetry

## Screens

| Screen | What it shows |
|---|---|
| **Today** | Your daily digest. Topics that matter, with AI-generated summaries and stats |
| **Pulse** | Custom widgets dashboard. Build widgets from extracted notification data |
| **All** | Complete notification history |
| **Tune** | App-level tuning, theme, focus text, cloud toggle, and developer settings |

## Tech stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 + Edge-to-edge |
| Architecture | MVVM, Hilt DI, Navigation Compose |
| Local storage | Room |
| Background work | WorkManager + Foreground Service |
| Inference engine | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) with tool use |
| Language model | Gemma 4 E2B (on-device) |
| Embeddings | EmbeddingGemma 300M |
| NLP | SentencePiece tokenization |
| Build | CMake + NDK 28 |

## Install

### Prerequisites

- Android 12+ (API 31)
- ARM64 device
- **8 GB RAM minimum** (12 GB RAM recommended)
- **Chipset more capable or equal to Snapdragon 8 Gen 2**
- ~3 GB free storage (model files)

### Setup

**1. Install the APK**

Download the latest APK from [Releases](https://github.com/darahas/focal/releases) and install:

```bash
adb install focal-0.1.0-release.apk
```

**2. Developer settings**

Open the app, navigate to **Tune &rarr; Developer Settings**, and configure:

| Setting | Action |
|---|---|
| Notification Access | Grant permission |
| Battery optimization | Disable for Focal |
| Model | Keep `Gemma 4 E2B` selected |
| Download | Tap to fetch `gemma-4-E2B-it.litertlm` |
| Start | Tap to load the model into memory |

**3. Install the embedding model**

Download both files from Hugging Face:
> **Note:** You must accept the [Gemma terms of service](https://ai.google.dev/gemma/terms) before using the embedding models in hugging face.

| File | Link |
|---|---|
| `embeddinggemma-300M_seq1024_mixed-precision.tflite` | [Download](https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/embeddinggemma-300M_seq1024_mixed-precision.tflite) |
| `sentencepiece.model` | [Download](https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/sentencepiece.model) |

Push them to the device:

```bash
adb shell mkdir -p /sdcard/Android/data/com.focal/files/models/embeddings
adb push embeddinggemma-300M_seq1024_mixed-precision.tflite /sdcard/Android/data/com.focal/files/models/embeddings/
adb push sentencepiece.model /sdcard/Android/data/com.focal/files/models/embeddings/
```

**4. Verify**

Reopen Focal. The **Embedding Model** indicator should show `Model found`.

---

## How it works

1. **Listen**. Focal runs a `NotificationListenerService` that intercepts all notifications
2. **Classify**. Each notification is classified via the on-device LLM (with optional cloud fallback) into Matters / Noise / Auto
3. **Extract**. Structured data (finance, work items, personal messages, deliveries, bank transactions) is extracted via LLM tool calls
4. **Cluster**. Matters notifications are embedded and clustered into topics by semantic similarity and channel grouping
5. **Summarize**. Each topic gets an AI-generated headline and summary
6. **Widgetize**. Extracted data powers user-created widgets on the Pulse dashboard

## Build from source

```bash
git clone https://github.com/darahas/focal.git
cd focal
./gradlew assembleRelease
```

Requires Android Studio Hedgehog+ and NDK 28.0.

## Documentation

Full documentation is available at [focal.shobhi1310.dev](https://shobhi1310.github.io/focal-glint):

- [How Focal works](https://shobhi1310.github.io/focal-glint/) . the full story from notification to widget
- [Architecture & internals](https://shobhi1310.github.io/focal-glint/architecture) . database schema, DI wiring, services
- [Classification & rules](https://shobhi1310.github.io/focal-glint/classification-and-rules) . how each notification is triaged
- [LLM inference pipeline](https://shobhi1310.github.io/focal-glint/inference-pipeline) . model invocation, workers, priority queuing
- [Embeddings & clustering](https://shobhi1310.github.io/focal-glint/embeddings-and-clustering) . JNI bridge, vector math, topic engine
- [Extraction & widgets](https://shobhi1310.github.io/focal-glint/extraction-and-widgets) . tool-based extraction, Pulse dashboard
- [Technology decisions](https://shobhi1310.github.io/focal-glint/tech-decisions) . C++, CPU embeddings, library conflicts
- [Fine-tuning guide](https://shobhi1310.github.io/focal-glint/finetuning-guide) . dataset design, training, and model export

Source for the docs is in the [`docs/`](docs/) folder.

## License

The Gemma model weights used by Focal are subject to the [Gemma terms of service](https://ai.google.dev/gemma/terms). You must accept these terms before downloading or using the models.

The Focal application source code is distributed under a separate license (see `LICENSE` in the repository root).
