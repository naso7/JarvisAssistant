//
// Created by Naso on 12.11.2026.
//

#include "whisper_engine.h"
#include <android/log.h>

#define LOG_TAG "JarvisWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

WhisperEngine::~WhisperEngine() {
    if (ctx) {
        whisper_free(ctx);
        ctx = nullptr;
    }
}

bool WhisperEngine::loadModel(const std::string& modelPath) {
    if (ctx) {
        whisper_free(ctx);
        ctx = nullptr;
    }

    whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);

    if (!ctx) {
        LOGE("Не удалось загрузить модель Whisper из: %s", modelPath.c_str());
        return false;
    }
    LOGI("Модель Whisper успешно загружена!");
    return true;
}

std::string WhisperEngine::transcribe(const std::vector<float>& pcmData) {
    if (!ctx || pcmData.empty()) return "";

    // Настраиваем параметры распознавания для мобильного процессора
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.language         = "ru"; // Жестко задаем русский язык для скорости и точности
    wparams.n_threads        = 4;    // 4 производительных ядра смартфона
    wparams.no_context       = true; // Не тащим контекст прошлых фраз (экономит память)

    if (whisper_full(ctx, wparams, pcmData.data(), pcmData.size()) != 0) {
        LOGE("Ошибка распознавания аудио в whisper_full");
        return "";
    }

    std::string resultText;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            resultText += text;
        }
    }

    return resultText;
}
