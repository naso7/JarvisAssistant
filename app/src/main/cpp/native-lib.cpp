#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include "core/whisper_engine.h"
#include "core/llama_engine.h"

static std::unique_ptr<WhisperEngine> g_whisper;
static std::unique_ptr<LlamaEngine> g_llama;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_portfolio_jarvis_JarvisNativeBridge_initEngines(
        JNIEnv* env,
        jobject /* this */,
        jstring whisperModelPath,
        jstring llamaModelPath) {

    const char* wPath = env->GetStringUTFChars(whisperModelPath, nullptr);
    const char* lPath = env->GetStringUTFChars(llamaModelPath, nullptr);

    g_whisper = std::make_unique<WhisperEngine>();
    g_llama = std::make_unique<LlamaEngine>();

    bool wOk = g_whisper->loadModel(wPath);
    bool lOk = g_llama->loadModel(lPath);

    env->ReleaseStringUTFChars(whisperModelPath, wPath);
    env->ReleaseStringUTFChars(llamaModelPath, lPath);

    return (wOk && lOk) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_portfolio_jarvis_JarvisNativeBridge_transcribeAudio(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray audioSamples) {

    if (!g_whisper || !g_whisper->isLoaded()) {
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(audioSamples);
    std::vector<float> pcmData(len);
    env->GetFloatArrayRegion(audioSamples, 0, len, pcmData.data());

    std::string text = g_whisper->transcribe(pcmData);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_portfolio_jarvis_JarvisNativeBridge_askJarvis(
        JNIEnv* env,
        jobject thiz,
        jstring userText) {

    if (!g_llama || !g_llama->isLoaded()) {
        return env->NewStringUTF("Системы не инициализированы, сэр.");
    }

    const char* uText = env->GetStringUTFChars(userText, nullptr);
    std::string prompt(uText);
    env->ReleaseStringUTFChars(userText, uText);

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID onTokenMethod = env->GetMethodID(clazz, "onTokenGenerated", "(Ljava/lang/String;)V");

    std::string fullResponse = g_llama->generateResponse(prompt, [&](const std::string& token) {
        if (onTokenMethod != nullptr) {
            jstring jToken = env->NewStringUTF(token.c_str());
            env->CallVoidMethod(thiz, onTokenMethod, jToken);
            env->DeleteLocalRef(jToken);
        }
    });

    return env->NewStringUTF(fullResponse.c_str());
}

JNIEXPORT void JNICALL
Java_com_portfolio_jarvis_JarvisNativeBridge_stopGeneration(JNIEnv* /* env */, jobject /* this */) {
    if (g_llama) g_llama->stopGeneration();
}

JNIEXPORT void JNICALL
Java_com_portfolio_jarvis_JarvisNativeBridge_clearHistory(JNIEnv* /* env */, jobject /* this */) {
    if (g_llama) g_llama->clearHistory();
}

} // extern "C"
