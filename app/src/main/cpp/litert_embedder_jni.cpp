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

#define CHECK(s, msg, ret)                                           \
    do {                                                              \
        if ((s) != kLiteRtStatusOk) {                                 \
            LOGE(msg ": status=%d", static_cast<int>(s));             \
            return (ret);                                             \
        }                                                             \
    } while (0)

struct EmbedHandle {
    LiteRtEnvironment   env        = nullptr;
    LiteRtModel         model      = nullptr;
    LiteRtCompiledModel compiled   = nullptr;
    LiteRtTensorBuffer  in_ids     = nullptr;
    LiteRtTensorBuffer  in_mask    = nullptr;  // null when model has only 1 input
    LiteRtTensorBuffer  output     = nullptr;

    int    num_inputs    = 0;
    int    max_seq_len   = 0;
    int    embedding_dim = 0;
    size_t input_bytes   = 0;
    size_t output_bytes  = 0;

    LiteRtRankedTensorType in_type  {};
    LiteRtRankedTensorType out_type {};
};

static size_t totalElements(const LiteRtLayout& l) {
    size_t n = 1;
    for (unsigned i = 0; i < l.rank; ++i) n *= static_cast<size_t>(l.dimensions[i]);
    return n;
}

static size_t elemBytes(LiteRtElementType et) {
    switch (et) {
        case kLiteRtElementTypeInt32:   return 4;
        case kLiteRtElementTypeFloat32: return 4;
        case kLiteRtElementTypeInt64:   return 8;
        default:                        return 4;
    }
}

static bool initHandle(EmbedHandle* h, const char* model_path) {
    LiteRtStatus s;

    s = LiteRtCreateEnvironment(0, nullptr, &h->env);
    CHECK(s, "LiteRtCreateEnvironment", false);

    s = LiteRtCreateModelFromFile(model_path, &h->model);
    CHECK(s, "LiteRtCreateModelFromFile", false);

    LiteRtOptions opts = nullptr;
    s = LiteRtCreateOptions(&opts);
    CHECK(s, "LiteRtCreateOptions", false);

    s = LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu);
    if (s != kLiteRtStatusOk) {
        LOGE("LiteRtSetOptionsHardwareAccelerators: %d", static_cast<int>(s));
        LiteRtDestroyOptions(opts);
        return false;
    }

    s = LiteRtCreateCompiledModel(h->env, h->model, opts, &h->compiled);
    LiteRtDestroyOptions(opts);
    CHECK(s, "LiteRtCreateCompiledModel", false);

    // Inspect the main subgraph to discover input/output shapes
    LiteRtParamIndex main_idx = 0;
    LiteRtGetMainModelSubgraphIndex(h->model, &main_idx);

    LiteRtSubgraph sg = nullptr;
    s = LiteRtGetModelSubgraph(h->model, main_idx, &sg);
    CHECK(s, "LiteRtGetModelSubgraph", false);

    LiteRtParamIndex n_in = 0, n_out = 0;
    LiteRtGetNumSubgraphInputs(sg, &n_in);
    LiteRtGetNumSubgraphOutputs(sg, &n_out);
    h->num_inputs = static_cast<int>(n_in);

    LiteRtTensor in0 = nullptr;
    s = LiteRtGetSubgraphInput(sg, 0, &in0);
    CHECK(s, "LiteRtGetSubgraphInput[0]", false);
    s = LiteRtGetRankedTensorType(in0, &h->in_type);
    CHECK(s, "LiteRtGetRankedTensorType[in]", false);

    size_t in_elems  = totalElements(h->in_type.layout);
    h->max_seq_len   = h->in_type.layout.dimensions[h->in_type.layout.rank - 1];
    h->input_bytes   = in_elems * elemBytes(h->in_type.element_type);

    LiteRtTensor out0 = nullptr;
    s = LiteRtGetSubgraphOutput(sg, 0, &out0);
    CHECK(s, "LiteRtGetSubgraphOutput[0]", false);
    s = LiteRtGetRankedTensorType(out0, &h->out_type);
    CHECK(s, "LiteRtGetRankedTensorType[out]", false);

    size_t out_elems = totalElements(h->out_type.layout);
    h->embedding_dim = static_cast<int>(out_elems);
    h->output_bytes  = out_elems * elemBytes(h->out_type.element_type);

    LOGI("loaded: inputs=%d max_seq_len=%d embedding_dim=%d",
         h->num_inputs, h->max_seq_len, h->embedding_dim);

    // Pre-allocate managed host-memory tensor buffers (reused every embed call)
    s = LiteRtCreateManagedTensorBuffer(h->env, kLiteRtTensorBufferTypeHostMemory,
                                        &h->in_type, h->input_bytes, &h->in_ids);
    CHECK(s, "CreateManagedTensorBuffer[in_ids]", false);

    if (h->num_inputs >= 2) {
        s = LiteRtCreateManagedTensorBuffer(h->env, kLiteRtTensorBufferTypeHostMemory,
                                            &h->in_type, h->input_bytes, &h->in_mask);
        CHECK(s, "CreateManagedTensorBuffer[in_mask]", false);
    }

    s = LiteRtCreateManagedTensorBuffer(h->env, kLiteRtTensorBufferTypeHostMemory,
                                        &h->out_type, h->output_bytes, &h->output);
    CHECK(s, "CreateManagedTensorBuffer[output]", false);

    return true;
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
        jclass ex = jenv->FindClass("java/lang/RuntimeException");
        jenv->ThrowNew(ex, "LiteRt embedding model failed to initialize");
        return 0L;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jfloatArray JNICALL
Java_com_focal_intelligence_LiteRtEmbedderJni_nativeEmbed(
        JNIEnv* jenv, jobject /*thiz*/, jlong handle,
        jintArray input_ids_j, jintArray input_mask_j) {
    auto* h = reinterpret_cast<EmbedHandle*>(handle);
    if (!h || !h->compiled) return nullptr;

    auto n_ints = static_cast<jsize>(h->input_bytes / 4);
    void* ptr   = nullptr;
    LiteRtStatus s;

    // Fill input_ids buffer
    s = LiteRtLockTensorBuffer(h->in_ids, &ptr, kLiteRtTensorBufferLockModeWrite);
    CHECK(s, "Lock[in_ids]", nullptr);
    jenv->GetIntArrayRegion(input_ids_j, 0, n_ints, reinterpret_cast<jint*>(ptr));
    LiteRtUnlockTensorBuffer(h->in_ids);

    // Fill attention_mask buffer if model has a second input
    if (h->in_mask && input_mask_j) {
        s = LiteRtLockTensorBuffer(h->in_mask, &ptr, kLiteRtTensorBufferLockModeWrite);
        CHECK(s, "Lock[in_mask]", nullptr);
        jenv->GetIntArrayRegion(input_mask_j, 0, n_ints, reinterpret_cast<jint*>(ptr));
        LiteRtUnlockTensorBuffer(h->in_mask);
    }

    // Build input array and run
    LiteRtTensorBuffer in_bufs[2];
    in_bufs[0] = h->in_ids;
    size_t n_in = 1;
    if (h->in_mask && input_mask_j) {
        in_bufs[1] = h->in_mask;
        n_in = static_cast<size_t>(h->num_inputs);
    }

    s = LiteRtRunCompiledModel(h->compiled, 0, n_in, in_bufs, 1u, &h->output);
    CHECK(s, "LiteRtRunCompiledModel", nullptr);

    // Read embedding vector
    s = LiteRtLockTensorBuffer(h->output, &ptr, kLiteRtTensorBufferLockModeRead);
    CHECK(s, "Lock[output]", nullptr);
    jfloatArray result = jenv->NewFloatArray(h->embedding_dim);
    jenv->SetFloatArrayRegion(result, 0, h->embedding_dim,
                              reinterpret_cast<const jfloat*>(ptr));
    LiteRtUnlockTensorBuffer(h->output);
    return result;
}

JNIEXPORT void JNICALL
Java_com_focal_intelligence_LiteRtEmbedderJni_nativeClose(
        JNIEnv* /*jenv*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<EmbedHandle*>(handle);
    if (!h) return;
    if (h->in_ids)  LiteRtDestroyTensorBuffer(h->in_ids);
    if (h->in_mask) LiteRtDestroyTensorBuffer(h->in_mask);
    if (h->output)  LiteRtDestroyTensorBuffer(h->output);
    if (h->compiled) LiteRtDestroyCompiledModel(h->compiled);
    if (h->model)   LiteRtDestroyModel(h->model);
    if (h->env)     LiteRtDestroyEnvironment(h->env);
    delete h;
    LOGI("closed");
}

} // extern "C"
