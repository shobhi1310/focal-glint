---
title: Embeddings & clustering
description: How notifications are converted to vectors and grouped into topics using the on-device embedding pipeline
---

# Embeddings & clustering

Notifications are text. Computers need numbers. Focal converts every "matters" notification into a 768-dimensional vector, then groups similar vectors into topics. This page covers the entire embedding pipeline . from text to vector to topic.

---

## Overview

```
Notification text
    │
    ▼
EmbeddingTextFormatter formats query: "task: clustering | query: <text>"
    │
    ▼
SentencePieceTokenizer tokenizes text → token IDs (C++ via JNI)
    │
    ▼
LiteRtEmbedderJni runs EmbeddingGemma 300M (C++ TFLite inference)
    │
    ▼
768-dim float vector → L2 normalization
    │
    ▼
Stored as BLOB in notifications.embedding column
    │
    ▼
TopicEngine clusters by cosine similarity + channel matching
```

---

## SentencePiece tokenizer

### Why C++ SentencePiece

Gemma models use a SentencePiece tokenizer with a `.model` file. Google's SentencePiece library is C++. There is no maintained Java port with equivalent compatibility guarantees.

Using the C++ library ensures byte-for-byte identical tokenization to the model's training pipeline. A mismatch here would produce wrong embeddings.

### The JNI bridge

`SentencePieceTokenizer.kt` wraps the native code:

```kotlin
class SentencePieceTokenizer(private val modelPath: String) : Closeable {
    private external fun nativeCreate(modelPath: String): Long    // → SentencePieceProcessor*
    private external fun nativeEncode(handle: Long, text: String): IntArray  // → token IDs
    private external fun nativeClose(handle: Long)                // → delete processor

    fun initialize()  // synchronized, calls nativeCreate
    fun encode(text: String): IntArray  // volatile handle, calls nativeEncode
    override fun close()  // synchronized, calls nativeClose
}
```

The native handle is a `Long` that acts as an opaque pointer to a heap-allocated `sentencepiece::SentencePieceProcessor`.

### Native implementation (`sentencepiece_jni.cpp`)

```cpp
static jlong nativeCreate(JNIEnv* env, jobject, jstring modelPath) {
    auto* processor = new sentencepiece::SentencePieceProcessor();
    const auto status = processor->Load(path);
    if (!status.ok()) {
        delete processor;
        env->ThrowNew(IllegalArgumentException, status.ToString());
        return 0L;
    }
    return reinterpret_cast<jlong>(processor);
}

static jintArray nativeEncode(JNIEnv* env, jobject, jlong handle, jstring text) {
    auto* processor = reinterpret_cast<SentencePieceProcessor*>(handle);
    std::vector<int> ids;
    processor->Encode(utf, &ids);
    // Copy ids to jintArray and return
}
```

---

## EmbeddingGemmaLiteRtEmbedder

The embedding model is EmbeddingGemma 300M . a 300M parameter TFLite model that produces 768-dimensional embeddings.

### Kotlin-side pipeline

```kotlin
class EmbeddingGemmaLiteRtEmbedder(
    private val modelPath: String,
    @Suppress("unused") useGpu: Boolean  // always CPU, see ModelBackendPolicy
) {
    suspend fun embed(text: String): FloatArray {
        // 1. Tokenize
        val rawIds = tokenizer.encode(text)

        // 2. Build input: prepend BOS (2), pad to 1024 with 0
        val ids = buildInputIds(rawIds)
        val mask = IntArray(1024) { i -> if (ids[i] != 0) 1 else 0 }

        // 3. Native inference
        val raw = LiteRtEmbedderJni.nativeEmbed(handle, ids, mask)
            ?: throw IllegalStateException("Embedding inference failed")

        // 4. L2 normalize
        return VectorMath.l2Normalize(raw)
    }
}
```

### Input format

| Step | Details |
|---|---|
| Tokenization | Unicode → SentencePiece token IDs |
| BOS prepend | Token ID 2 prepended |
| Padding | Padded to 1024 tokens with token 0 |
| Attention mask | 1 for real tokens, 0 for padding |

### Why always CPU?

`ModelBackendPolicy.useGpuForEmbeddings()` hardcodes `false`:

```kotlin
object ModelBackendPolicy {
    // CPU-only: 100+ back-to-back GPU embeds starve SurfaceFlinger's render queue.
    fun useGpuForEmbeddings(llmUseGpu: Boolean): Boolean = false
}
```

When you embed 50+ notifications back to back, GPU inference blocks the Android render pipeline. The screen freezes until inference completes. EmbeddingGemma 300M is fast enough on CPU that this isn't a problem.

---

## LiteRtEmbedderJni (native inference)

### The JNI bridge

```kotlin
object LiteRtEmbedderJni {
    init { System.loadLibrary("focal_intelligence") }

    external fun nativeCreate(modelPath: String): Long
    external fun nativeEmbed(handle: Long, inputIds: IntArray, inputMask: IntArray): FloatArray?
    external fun nativeClose(handle: Long)
}
```

### Native implementation (`litert_embedder_jni.cpp`)

The native code manages the full LiteRT inference lifecycle:

**Init (`initHandle`):**
1. `LiteRtCreateEnvironment()` . creates runtime
2. `LiteRtCreateModelFromFile(path)` . loads `.tflite` from disk
3. `LiteRtCreateOptions()` + `LiteRtSetOptionsHardwareAccelerators(kLiteRtHwAcceleratorCpu)` . CPU only
4. `LiteRtCreateCompiledModel()` . compiles
5. Introspect tensor shapes: input IDs (1024), attention mask (1024), output (768)
6. Create managed tensor buffers via `LiteRtCreateManagedTensorBufferFromRequirements()`

**Inference (`nativeEmbed`):**
1. Copy `input_ids` IntArray → `in_ids` tensor buffer (direct HOST_MEMORY pointer, no lock/unlock)
2. Copy `attention_mask` IntArray → `in_mask` tensor buffer
3. `LiteRtRunCompiledModel()` . run inference
4. Read float embedding from output tensor buffer
5. Copy to `jfloatArray` and return

**Internal struct:**
```cpp
struct EmbedHandle {
    LiteRtEnvironment   env;
    LiteRtModel         model;
    LiteRtCompiledModel compiled;
    LiteRtTensorBuffer  in_ids, in_mask, output;
    int    num_inputs, max_seq_len, embedding_dim;
    size_t in_elems, mask_elems, out_elems;
};
```

### Zero-copy tensor access

The native code uses `LiteRtGetTensorBufferHostMemory` to get a direct pointer to the HOST_MEMORY backing of each tensor buffer. This avoids a lock/unlock round-trip and allows copying data with a single `GetIntArrayRegion` / `SetFloatArrayRegion` call.

---

## Vector math

`VectorMath.kt` provides the core math operations:

```kotlin
object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Float       // dot product (cosine similarity for normalized vectors)
    fun l2Normalize(vec: FloatArray): FloatArray       // unit-length normalization
    fun toBytes(vec: FloatArray): ByteArray            // float[] → BLOB for Room storage
    fun toFloats(bytes: ByteArray): FloatArray         // BLOB → float[] from Room
}
```

- **L2 normalization** scales the vector to unit length (Euclidean norm = 1.0)
- **Dot product** of two normalized vectors equals cosine similarity (range -1 to 1)
- Room stores embeddings as `BLOB` = raw float bytes (768 × 4 = 3072 bytes per embedding)

---

## TopicEngine

`TopicEngine` takes embedded notifications and assigns them to topics.

### Day window

Topics are scoped to a day window from **2 AM to 2 AM**:

```kotlin
object DayWindow {
    fun getWindow(nowMs: Long): Pair<Long, Long> {
        // If before 2 AM, use previous day's window
        // Returns [2AM today, 2AM tomorrow)
    }
}
```

### The algorithm

For each unprocessed "matters" notification:

**Phase 1: Channel-first matching**

```kotlin
if (notif.notificationKey != null) {
    for (topic in activeTopics) {
        if (topic.members.any { it.notificationKey == notif.notificationKey }) {
            appendToTopic(topic, notif)  // same channel → same topic, done
            return
        }
    }
}
```

Notifications from the same Android notification channel (e.g., the same WhatsApp conversation) always go to the same topic. No embedding comparison needed. This keeps conversation threads together.

**Phase 2: Cosine similarity matching**

```kotlin
for (topic in activeTopics) {
    var bestScore = -Float.MAX_VALUE
    for (member in topic.members) {
        val score = dot(notif.vec, member.vec)
        if (score > bestScore) bestScore = score
    }
    if (bestScore >= ASSIGN_THRESHOLD) bestTopicId = topic.id
}
```

If no channel match exists, compare the notification's embedding against every member of every active topic. Track the best pairwise cosine similarity. If it exceeds `TopicClusteringPolicy.ASSIGN_THRESHOLD`, add it to that topic.

**Phase 3: New topic creation**

If no channel match and no similarity match, create a new topic with:
- Provisional headline from the notification title
- Provisional summary from the notification body
- `needsNarrativeRegen = true` (will get an AI-generated narrative later)

### Topic updates

When a notification is added to an existing topic:
- `notificationIds` JSON array is updated
- `sourceApps` JSON array (deduplicated set of app names)
- `channelCount` incremented
- `needsNarrativeRegen = true` (the LLM summary is now stale)

---

## CMake build configuration

Both SentencePiece and the LiteRT embedder are built into a single shared library:

```cmake
add_library(focal_intelligence SHARED
    sentencepiece_jni.cpp
    litert_embedder_jni.cpp
)
target_link_libraries(focal_intelligence
    sentencepiece-static   # STATIC: SentencePiece v0.2.0 from GitHub
    LiteRt                 # SHARED IMPORTED: libLiteRt.so from litertlm AAR
    log                    # Android logcat
)
```

### FetchContent for SentencePiece

```cmake
FetchContent_Declare(sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG v0.2.0
)
set(SPM_ENABLE_SHARED OFF)      # static library, not shared .so
set(SPM_ENABLE_TCMALLOC OFF)    # not available on Android
FetchContent_MakeAvailable(sentencepiece)
```

### LiteRT headers from release tarball

LiteRT C API headers are downloaded from GitHub releases, not FetchContent. The CMake explains why:

```
# FetchContent creates a subbuild that inherits the Android NDK toolchain
# and fails compiler checks in CMake 3.22 cross-compilation builds
file(DOWNLOAD "https://github.com/./headers-2.1.4.tar.gz" .)
```

A minimal `build_config.h` is generated programmatically (GPU=0, NPU=0).

### Link-time stub for libLiteRt.so

The real `libLiteRt.so` comes from `litertlm-android:0.11.0` AAR at runtime. A stub is extracted at build time purely for CMake link resolution:

```kotlin
// build.gradle.kts
val stageLiteRtSo = tasks.registering {
    val aar = configurations.detachedConfiguration(
        dependencies.create("com.google.ai.edge.litertlm:litertlm-android:0.11.0@aar")
    ).resolve().single()
    val soFile = zipTree(aar).matching { include("jni/arm64-v8a/libLiteRt.so") }
    soFile.copyTo(File(dest, "libLiteRt.so"), overwrite = true)
}
```

---

## The full data flow

```
NotificationEntity (matters, not yet processed)
    │
    ▼
EmbeddingTextFormatter.buildRequest(notif)
    → "task: clustering | query: {appName}: {title}. {content}"
    │
    ▼
SentencePieceTokenizer.encode(text)
    → IntArray of token IDs
    │
    ▼
buildInputIds(rawIds)
    → prepend BOS (2), pad to 1024 with 0
    │
    ▼
LiteRtEmbedderJni.nativeEmbed(handle, ids, mask)
    → LiteRtCreateCompiledModel → LiteRtRunCompiledModel
    → FloatArray of 768 floats
    │
    ▼
VectorMath.l2Normalize(raw)
    → unit-length FloatArray
    │
    ▼
VectorMath.toBytes(vec)
    → ByteArray (768 * 4 bytes)
    │
    ▼
notificationRepository.setEmbedding(id, bytes)
    → stored in notifications.embedding BLOB column
    │
    ▼
TopicEngine.assignOrCreateTopic(notif, vec, .)
    → channel-first match → cosine similarity → create new
    │
    ▼
TopicEntity (headline, summary, notificationIds, needsNarrativeRegen=true)
```

---

## What to read next

- [LLM inference pipeline](inference-pipeline.html) . how narratives get generated from these topics
- [Extraction & widgets](extraction-and-widgets.html) . how extracted data feeds widgets
- [Technology decisions](tech-decisions.html) . why C++, why CPU, why arm64-only
- [Architecture & internals](architecture.html) . the overall component layout
