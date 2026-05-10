#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_tensor_buffer_types.h"

#define TAG "LiteRtEmbedJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// CHECK: logs and jumps to `cleanup` label on failure so all allocated objects
// are released before returning.  Every early exit goes through cleanup.
#define CHECK_GOTO(s, msg, label)                                        \
    do {                                                                  \
        if ((s) != kLiteRtStatusOk) {                                     \
            LOGE("%s: status=%d", (msg), static_cast<int>(s));            \
            goto label;                                                   \
        }                                                                 \
    } while (0)

#define CHECK(s, msg, ret)                                               \
    do {                                                                  \
        if ((s) != kLiteRtStatusOk) {                                     \
            LOGE("%s: status=%d", (msg), static_cast<int>(s));            \
            return (ret);                                                 \
        }                                                                 \
    } while (0)

// LiteRtGetNumLayoutElements is declared in litert_layout.h but not exported
// by libLiteRt.so from litertlm-android. Implement the same logic locally,
// treating any dimension <= 0 (dynamic shape) as an error.
static bool safeElementCount(const LiteRtLayout& l, size_t* out) {
    size_t n = 1;
    for (unsigned i = 0; i < l.rank; ++i) {
        if (l.dimensions[i] <= 0) {
            LOGE("safeElementCount: dynamic/zero dim %d at index %u",
                 l.dimensions[i], i);
            return false;
        }
        n *= static_cast<size_t>(l.dimensions[i]);
    }
    *out = n;
    return true;
}

struct EmbedHandle {
    LiteRtEnvironment   env      = nullptr;
    LiteRtModel         model    = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtTensorBuffer  in_ids   = nullptr;
    LiteRtTensorBuffer  in_mask  = nullptr;  // null when model has only 1 input
    LiteRtTensorBuffer  output   = nullptr;

    int    num_inputs    = 0;
    int    max_seq_len   = 0;
    int    embedding_dim = 0;
    size_t in_elems      = 0;   // element count for one input tensor
    size_t mask_elems    = 0;   // element count for attention_mask tensor
    size_t out_elems     = 0;   // element count for output tensor

    LiteRtRankedTensorType in_type   {};
    LiteRtRankedTensorType mask_type {};
    LiteRtRankedTensorType out_type  {};
};

// Destroy all LiteRt objects held by the handle (safe to call with nulls).
static void destroyHandle(EmbedHandle* h) {
    if (!h) return;
    if (h->in_ids)   LiteRtDestroyTensorBuffer(h->in_ids);
    if (h->in_mask)  LiteRtDestroyTensorBuffer(h->in_mask);
    if (h->output)   LiteRtDestroyTensorBuffer(h->output);
    if (h->compiled) LiteRtDestroyCompiledModel(h->compiled);
    if (h->model)    LiteRtDestroyModel(h->model);
    if (h->env)      LiteRtDestroyEnvironment(h->env);
    // Reset pointers so a second call is safe
    h->in_ids = h->in_mask = h->output = nullptr;
    h->compiled = nullptr;
    h->model    = nullptr;
    h->env      = nullptr;
}

static bool initHandle(EmbedHandle* h, const char* model_path) {
    LiteRtStatus s;
    LiteRtOptions opts = nullptr;

    s = LiteRtCreateEnvironment(0, nullptr, &h->env);
    CHECK_GOTO(s, "LiteRtCreateEnvironment", fail);

    s = LiteRtCreateModelFromFile(model_path, &h->model);
    CHECK_GOTO(s, "LiteRtCreateModelFromFile", fail);

    s = LiteRtCreateOptions(&opts);
    CHECK_GOTO(s, "LiteRtCreateOptions", fail);

    s = LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu);
    if (s != kLiteRtStatusOk) {
        LOGE("LiteRtSetOptionsHardwareAccelerators: %d", static_cast<int>(s));
        LiteRtDestroyOptions(opts);
        goto fail;
    }

    s = LiteRtCreateCompiledModel(h->env, h->model, opts, &h->compiled);
    LiteRtDestroyOptions(opts);
    opts = nullptr;
    CHECK_GOTO(s, "LiteRtCreateCompiledModel", fail);

    {
        // Discover input/output shapes from the main subgraph
        LiteRtParamIndex main_idx = 0;
        s = LiteRtGetMainModelSubgraphIndex(h->model, &main_idx);
        if (s != kLiteRtStatusOk) {
            LOGE("LiteRtGetMainModelSubgraphIndex: %d, using 0", static_cast<int>(s));
            main_idx = 0;
        }

        LiteRtSubgraph sg = nullptr;
        s = LiteRtGetModelSubgraph(h->model, main_idx, &sg);
        CHECK_GOTO(s, "LiteRtGetModelSubgraph", fail);

        LiteRtParamIndex n_in = 0, n_out = 0;
        s = LiteRtGetNumSubgraphInputs(sg, &n_in);
        CHECK_GOTO(s, "LiteRtGetNumSubgraphInputs", fail);
        s = LiteRtGetNumSubgraphOutputs(sg, &n_out);
        CHECK_GOTO(s, "LiteRtGetNumSubgraphOutputs", fail);
        h->num_inputs = static_cast<int>(n_in);

        // input[0]: input_ids (int32, shape [1, max_seq_len])
        LiteRtTensor in0 = nullptr;
        s = LiteRtGetSubgraphInput(sg, 0, &in0);
        CHECK_GOTO(s, "LiteRtGetSubgraphInput[0]", fail);
        s = LiteRtGetRankedTensorType(in0, &h->in_type);
        CHECK_GOTO(s, "LiteRtGetRankedTensorType[in0]", fail);
        if (!safeElementCount(h->in_type.layout, &h->in_elems)) goto fail;
        h->max_seq_len = (h->in_type.layout.rank >= 1)
                         ? h->in_type.layout.dimensions[h->in_type.layout.rank - 1] : 0;

        // input[1] (optional): attention_mask — query its type independently
        if (h->num_inputs >= 2) {
            LiteRtTensor in1 = nullptr;
            s = LiteRtGetSubgraphInput(sg, 1, &in1);
            CHECK_GOTO(s, "LiteRtGetSubgraphInput[1]", fail);
            s = LiteRtGetRankedTensorType(in1, &h->mask_type);
            CHECK_GOTO(s, "LiteRtGetRankedTensorType[in1]", fail);
            if (!safeElementCount(h->mask_type.layout, &h->mask_elems)) goto fail;
        }

        // output[0]: embedding vector (float32, shape [1, embedding_dim])
        LiteRtTensor out0 = nullptr;
        s = LiteRtGetSubgraphOutput(sg, 0, &out0);
        CHECK_GOTO(s, "LiteRtGetSubgraphOutput[0]", fail);
        s = LiteRtGetRankedTensorType(out0, &h->out_type);
        CHECK_GOTO(s, "LiteRtGetRankedTensorType[out0]", fail);
        if (!safeElementCount(h->out_type.layout, &h->out_elems)) goto fail;
        h->embedding_dim = static_cast<int>(h->out_elems);

        LOGI("loaded: inputs=%d max_seq_len=%d embedding_dim=%d",
             h->num_inputs, h->max_seq_len, h->embedding_dim);
    }

    {
        // Create managed HOST_MEMORY tensor buffers from compiled-model buffer
        // requirements — this ensures correct size and alignment for the runtime.
        LiteRtTensorBufferRequirements in_reqs = nullptr;
        s = LiteRtGetCompiledModelInputBufferRequirements(
                h->compiled, 0, 0, &in_reqs);
        CHECK_GOTO(s, "GetInputBufferRequirements[0]", fail);

        s = LiteRtCreateManagedTensorBufferFromRequirements(
                h->env, &h->in_type, in_reqs, &h->in_ids);
        CHECK_GOTO(s, "CreateManagedTensorBuffer[in_ids]", fail);

        if (h->num_inputs >= 2) {
            LiteRtTensorBufferRequirements mask_reqs = nullptr;
            s = LiteRtGetCompiledModelInputBufferRequirements(
                    h->compiled, 0, 1, &mask_reqs);
            CHECK_GOTO(s, "GetInputBufferRequirements[1]", fail);
            s = LiteRtCreateManagedTensorBufferFromRequirements(
                    h->env, &h->mask_type, mask_reqs, &h->in_mask);
            CHECK_GOTO(s, "CreateManagedTensorBuffer[in_mask]", fail);
        }

        LiteRtTensorBufferRequirements out_reqs = nullptr;
        s = LiteRtGetCompiledModelOutputBufferRequirements(
                h->compiled, 0, 0, &out_reqs);
        CHECK_GOTO(s, "GetOutputBufferRequirements[0]", fail);
        s = LiteRtCreateManagedTensorBufferFromRequirements(
                h->env, &h->out_type, out_reqs, &h->output);
        CHECK_GOTO(s, "CreateManagedTensorBuffer[output]", fail);
    }

    return true;

fail:
    // All pointers in h that are non-null are destroyed here before returning.
    destroyHandle(h);
    return false;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_focal_intelligence_LiteRtEmbedderJni_nativeCreate(
        JNIEnv* jenv, jobject /*thiz*/, jstring model_path_j) {
    const char* path = jenv->GetStringUTFChars(model_path_j, nullptr);
    auto* h = new EmbedHandle();
    bool ok = initHandle(h, path);
    jenv->ReleaseStringUTFChars(model_path_j, path);
    if (!ok) {
        delete h;
        return 0L;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jfloatArray JNICALL
Java_com_focal_intelligence_LiteRtEmbedderJni_nativeEmbed(
        JNIEnv* jenv, jobject /*thiz*/, jlong handle,
        jintArray input_ids_j, jintArray input_mask_j) {
    auto* h = reinterpret_cast<EmbedHandle*>(handle);
    if (!h || !h->compiled || !input_ids_j) return nullptr;

    LiteRtStatus s;
    void* ptr = nullptr;

    // Write input_ids: use LiteRtGetTensorBufferHostMemory to get direct
    // pointer to the HOST_MEMORY backing buffer without a lock/unlock round-trip.
    s = LiteRtGetTensorBufferHostMemory(h->in_ids, &ptr);
    CHECK(s, "GetHostMemory[in_ids]", nullptr);
    if (!ptr) { LOGE("GetHostMemory[in_ids] returned null"); return nullptr; }
    jenv->GetIntArrayRegion(input_ids_j, 0,
                            static_cast<jsize>(h->in_elems),
                            reinterpret_cast<jint*>(ptr));
    if (jenv->ExceptionCheck()) {
        LOGE("GetIntArrayRegion[in_ids] threw — array too short?");
        return nullptr;
    }

    if (h->in_mask && input_mask_j) {
        s = LiteRtGetTensorBufferHostMemory(h->in_mask, &ptr);
        CHECK(s, "GetHostMemory[in_mask]", nullptr);
        if (!ptr) { LOGE("GetHostMemory[in_mask] returned null"); return nullptr; }
        jenv->GetIntArrayRegion(input_mask_j, 0,
                                static_cast<jsize>(h->mask_elems),
                                reinterpret_cast<jint*>(ptr));
        if (jenv->ExceptionCheck()) {
            LOGE("GetIntArrayRegion[in_mask] threw");
            return nullptr;
        }
    }

    LiteRtTensorBuffer in_bufs[2];
    in_bufs[0] = h->in_ids;
    size_t n_in = 1;
    if (h->in_mask && input_mask_j) {
        in_bufs[1] = h->in_mask;
        n_in = static_cast<size_t>(h->num_inputs);
    }

    // Use a local copy so the runtime cannot mutate h->output (defensive).
    LiteRtTensorBuffer out_buf = h->output;
    s = LiteRtRunCompiledModel(h->compiled, 0, n_in, in_bufs, 1u, &out_buf);
    CHECK(s, "LiteRtRunCompiledModel", nullptr);

    // Read embedding vector from HOST_MEMORY backing buffer directly.
    ptr = nullptr;
    s = LiteRtGetTensorBufferHostMemory(out_buf, &ptr);
    CHECK(s, "GetHostMemory[output]", nullptr);
    if (!ptr) { LOGE("GetHostMemory[output] returned null"); return nullptr; }

    jfloatArray result = jenv->NewFloatArray(h->embedding_dim);
    if (!result) {
        LOGE("NewFloatArray(%d) returned null — OOM", h->embedding_dim);
        return nullptr;
    }
    jenv->SetFloatArrayRegion(result, 0, h->embedding_dim,
                              reinterpret_cast<const jfloat*>(ptr));
    return result;
}

JNIEXPORT void JNICALL
Java_com_focal_intelligence_LiteRtEmbedderJni_nativeClose(
        JNIEnv* /*jenv*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<EmbedHandle*>(handle);
    if (!h) return;
    destroyHandle(h);
    delete h;
    LOGI("closed");
}

} // extern "C"
