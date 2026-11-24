//
// Created by Naso on 12.11.2026.
//

#ifndef JARVISASSISTANT_WHISPER_ENGINE_H
#define JARVISASSISTANT_WHISPER_ENGINE_H


#pragma once
#include <string>
#include <vector>
#include "whisper.h"

class WhisperEngine {
public:
    WhisperEngine() = default;
    ~WhisperEngine();

    // Загрузка модели ggml-tiny.bin или ggml-base.bin
    bool loadModel(const std::string& modelPath);

    // Распознавание аудио-буфера (16kHz, моно, float от -1.0 до 1.0)
    std::string transcribe(const std::vector<float>& pcmData);

    bool isLoaded() const { return ctx != nullptr; }

private:
    struct whisper_context* ctx = nullptr;
};


#endif //JARVISASSISTANT_WHISPER_ENGINE_H
