# Async GPU Embeddings via LiteRT JNI — Implementation Plan

## Problem
`EmbeddingGemmaLiteRtEmbedder.embed()` uses `CompiledModel.run()` which calls
`clWaitForEvents()` synchronously. Running 100+ embeds back-to-back saturates
the Adreno GPU command queue and starves SurfaceFlinger's render pipeline → UI freeze.

## Why Not `RunAsync` from Kotlin
The LiteRT Kotlin API (`CompiledModel`) only exposes synchronous `run()`.
`RunAsync` exists only in the C API (`LiteRtRunCompiledModelAsync`).
`TensorBuffer` has no event-attachment API in Kotlin either.

## C API Flow (confirmed from LiteRT source)

```cpp
// litert/c/litert_compiled_model.h
LiteRtStatus LiteRtRunCompiledModelAsync(
    LiteRtCompiledModel model,
    LiteRtParamIndex signature_index,
    size_t num_inputs,  LiteRtTensorBuffer* inputs,
    size_t num_outputs, LiteRtTensorBuffer* outputs,
    bool* async_out);   // set to true if GPU async was actually used

// litert/c/litert_tensor_buffer.h
// Lock() auto-waits for any attached GPU event before mapping host memory
LiteRtStatus LiteRtLockTensorBuffer(LiteRtTensorBuffer buf, void** host_ptr,
                                    LiteRtTensorBufferLockMode mode);
LiteRtStatus LiteRtUnlockTensorBuffer(LiteRtTensorBuffer buf);
```

After `RunAsync`, output buffers have a GPU sync event attached.
`LiteRtLockTensorBuffer(..., READ)` waits for that event then maps host memory.
No explicit `LiteRtWaitEvent` needed.

## CMake Linking
LiteRT AAR exposes Prefab; add to `app/build.gradle.kts`:
```kotlin
android { buildFeatures { prefab = true } }
```
Then in `app/src/main/cpp/CMakeLists.txt`:
```cmake
find_package(litert REQUIRED CONFIG)
target_link_libraries(focal_intelligence litert::litert_c_api)
```
Headers live at `C:/Users/darur/AndroidStudioProjects/LiteRT/litert/c/`.

## Implementation Sketch

### `app/src/main/cpp/litert_async_embedder.cpp`
```cpp
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_environment.h"
#include <jni.h>

// nativeCreate(modelPath): creates LiteRtCompiledModel, pre-allocates buffers
// nativeEmbed(handle, inputIds, mask): RunAsync → Lock → copy floats → Unlock
// nativeDestroy(handle): destroys model + buffers
```

### `app/src/main/java/com/focal/intelligence/LiteRtAsyncEmbedder.kt`
```kotlin
class LiteRtAsyncEmbedder(modelPath: String, useGpu: Boolean) : Closeable {
    private val handle = nativeCreate(modelPath, useGpu)
    suspend fun embed(ids: IntArray, mask: IntArray): FloatArray =
        withContext(Dispatchers.IO) { nativeEmbed(handle, ids, mask) }
    override fun close() = nativeDestroy(handle)
    private external fun nativeCreate(path: String, gpu: Boolean): Long
    private external fun nativeEmbed(h: Long, ids: IntArray, mask: IntArray): FloatArray
    private external fun nativeDestroy(h: Long)
    companion object { init { System.loadLibrary("focal_intelligence") } }
}
```

## GPU Scheduling Note
`RunAsync` does NOT reduce total GPU occupancy — the GPU is just as busy.
The benefit is CPU thread efficiency (non-blocking) and potential for GPU-side
kernel pipelining if we double-buffer (separate input/output buffers per frame).

To actually give render pipeline breathing room with GPU embeddings, combine
`RunAsync` with a `delay(16)` every 10 embeds (one frame gap per chunk).

## Why We're Using CPU Instead (for now)
Simpler, zero GPU contention, Gemma Embedding 0.12B is fast enough on CPU.
`ModelBackendPolicy.useGpuForEmbeddings()` returns `false`.
Revisit if CPU embed latency becomes a bottleneck on low-end devices.
