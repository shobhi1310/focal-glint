#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "sentencepiece_processor.h"

#define TAG "SentencePieceJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sentencepiece::SentencePieceProcessor* fromHandle(jlong handle) {
    return reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_focal_intelligence_SentencePieceTokenizer_nativeCreate(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    auto* processor = new sentencepiece::SentencePieceProcessor();
    const auto status = processor->Load(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!status.ok()) {
        LOGE("Failed to load model: %s", status.ToString().c_str());
        delete processor;
        jclass ex = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(ex, ("Tokenizer load failed: " + status.ToString()).c_str());
        return 0L;
    }
    return reinterpret_cast<jlong>(processor);
}

JNIEXPORT jintArray JNICALL
Java_com_focal_intelligence_SentencePieceTokenizer_nativeEncode(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text) {
    auto* processor = fromHandle(handle);
    const char* utf = env->GetStringUTFChars(text, nullptr);
    std::vector<int> ids;
    const auto status = processor->Encode(utf, &ids);
    env->ReleaseStringUTFChars(text, utf);

    if (!status.ok()) {
        LOGE("Encode failed: %s", status.ToString().c_str());
        return env->NewIntArray(0);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(ids.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()),
                           reinterpret_cast<const jint*>(ids.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_focal_intelligence_SentencePieceTokenizer_nativeClose(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete fromHandle(handle);
}

} // extern "C"
