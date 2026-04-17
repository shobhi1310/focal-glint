# Focal 💎

**Focus, Locally.** An on-device notification triage and semantic summarization engine powered by Edge LLMs.

Focal is a privacy-first notification management system that runs entirely on your device's NPU/GPU. It transforms the "noisy" stream of mobile notifications into a structured, actionable "Daily Digest" without a single byte of your personal data ever leaving the device.

## 🌟 Key Features

- **Semantic Triage:** Automatically categorizes notifications into "Urgent," "Informational," or "Noise" based on your current context and habits.

- **Threaded Summarization:** Uses a quantized Gemma-2b model to condense long WhatsApp or Slack threads into a single-sentence preview on your lock screen.

- **Privacy-by-Design:** Processing happens in the device RAM. No cloud APIs, no data mining, 100% offline.

- **Actionable Insights:** "Smart Replies" generated based on the context of the entire thread, not just the last message.

- **Zero-Latency:** Immediate processing of incoming push events via local inference.

## 🏗️ Technical Architecture

Focal is designed to work within the memory constraints of modern mobile devices (8GB+ RAM):

- **Model:** Gemma-2b-IT (4-bit quantized via MLC LLM).
- **Inference Engine:** WebGPU / Vulkan / Metal (via TVM Unity).
- **Orchestration:** Rust-based background service for low-footprint lifecycle management.
- **Data Layer:** Local vector store (HNSW) for personalized context retrieval without cloud sync.

## 🚀 Getting Started

### Prerequisites

- Android 12+ or iOS 15+
- Device with NPU support (Snapdragon 8 Gen 2+, Apple A15+)
- 400MB free storage for model weights

### Installation

```bash
# Clone the repository
git clone https://github.com/shobhi1310/focal-glint.git

# Install dependencies
cd focal-glint && npm install

# Download quantized model weights (local cache)
npm run fetch-weights
```

## 🔒 Security Policy

Focal is built on the principle of **Zero-Trust Intelligence**.

- **Internet Access:** The application core has no network permissions after the initial model download.
- **Persistence:** Summaries are stored in an encrypted local database and purged every 24 hours.

## 🗺️ Roadmap

- [ ] v1.0: Basic summarization for SMS and WhatsApp.
- [ ] v1.1: Context-aware "Do Not Disturb" (auto-silencing low-priority alerts).
- [ ] v1.2: Multi-modal support (summarizing image-based notifications).

## 📄 License

MIT License. See [LICENSE](LICENSE) for details.
