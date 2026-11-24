//
// Created by Naso on 12.11.2026.
//

#ifndef JARVISASSISTANT_LLAMA_ENGINE_H
#define JARVISASSISTANT_LLAMA_ENGINE_H


#pragma once
#include <string>
#include <vector>
#include <functional>
#include <atomic>
#include "llama.h"

class LlamaEngine {
public:
    using TokenCallback = std::function<void(const std::string&)>;

    LlamaEngine();
    ~LlamaEngine();

    bool loadModel(const std::string& modelPath);

    // Генерация ответа с потоковой выдачей букв в callback
    std::string generateResponse(const std::string& userMessage, TokenCallback onToken);

    void clearHistory();
    void stopGeneration();

    bool isLoaded() const { return model != nullptr && ctx != nullptr; }

private:
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int global_pos = 0;
    bool isFirstTurn = true;
    std::atomic<bool> stopFlag{false};

    void resetContext();
};


#endif //JARVISASSISTANT_LLAMA_ENGINE_H
