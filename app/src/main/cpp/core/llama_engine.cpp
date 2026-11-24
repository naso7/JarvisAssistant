//
// Created by Naso on 12.11.2026.
//

#include "llama_engine.h"
#include <android/log.h>
#include <cstring>
#include <chrono>

#define LOG_TAG "JarvisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

LlamaEngine::LlamaEngine() {
    llama_backend_init();
}

LlamaEngine::~LlamaEngine() {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    llama_backend_free();
}

void LlamaEngine::resetContextInternal() {
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model) {
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 1024;
        ctx_params.n_batch = 512; // Увеличиваем лимит пачки до 512 токенов, чтобы избежать GGML_ASSERT
        ctx_params.n_threads = 4;
        ctx = llama_init_from_model(model, ctx_params);
    }
    isFirstTurn = true;
}

std::string LlamaEngine::generateResponse(const std::string& userMessage, TokenCallback onToken) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!ctx || !model) return "";

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) return "";

    stopFlag.store(false);

    // Сбрасываем KV-кэш перед новым вопросом, чтобы позиции токенов всегда начинались с 0 без конфликтов!
    llama_memory_clear(llama_get_memory(ctx), true);

    std::vector<llama_chat_message> messages;
    std::string sysText =
            "Ты — Джарвис, голосовой ИИ-помощник. Отвечай кратко (1-2 предложения), вежливо, с юмором, обращайся 'сэр'. "
            "Понимай опечатки речи (например 'фанарик'='фонарик'). Переводи слова-числа в цифры. "
            "Для выполнения команд ОБЯЗАТЕЛЬНО пиши тег в самом конце ответа:\n"
            "- Включить фонарик: [ACTION:FLASHLIGHT_ON]\n"
            "- Выключить фонарик: [ACTION:FLASHLIGHT_OFF]\n"
            "- Таймер на N минут: [ACTION:TIMER:N] (Пример: 'Таймер на 10 минут запущен, сэр. [ACTION:TIMER:10]')\n"
            "- Будильник на время ЧЧ:ММ: [ACTION:ALARM_TIME:ЧЧ:ММ] (Пример: 'Будильник на 07:30 установлен, сэр. [ACTION:ALARM_TIME:07:30]')\n"
            "- Будильник через N минут: [ACTION:ALARM_IN:N] (Пример: 'Будильник через 15 минут активирован, сэр. [ACTION:ALARM_IN:15]')";

    messages.push_back({"system", sysText.c_str()});
    messages.push_back({"user", userMessage.c_str()});

    const char* tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> formatted(userMessage.size() * 2 + 512);
    int new_len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());

    if (new_len > (int)formatted.size()) {
        formatted.resize(new_len + 1);
        new_len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());
    }

    std::string prompt = (new_len > 0)
                         ? std::string(formatted.data(), new_len)
                         : ("User: " + userMessage + "\nAssistant: ");

    std::vector<llama_token> tokens(prompt.size() + 8);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), false, true);
    if (n_tokens < 0) return "";
    tokens.resize(n_tokens);

    // Гарантируем, что входной запрос никогда не превысит размер n_batch (512)
    if (tokens.size() > 512) {
        tokens.resize(512);
    }

    llama_batch prompt_batch = llama_batch_get_one(tokens.data(), tokens.size());

    if (llama_decode(ctx, prompt_batch) != 0) {
        LOGE("Ошибка llama_decode на входном промпте!");
        return "";
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string fullResponse;
    const int max_tokens = 100;
    auto startTime = std::chrono::steady_clock::now();

    for (int i = 0; i < max_tokens; ++i) {
        if (stopFlag.load()) break;

        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - startTime).count();
        if (elapsed > 15) break;

        llama_token id = llama_sampler_sample(smpl, ctx, -1);

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        std::vector<char> buf(64, '\0');
        int n = llama_token_to_piece(vocab, id, buf.data(), buf.size(), 0, true);
        if (n > 0) {
            std::string piece(buf.data(), n);
            fullResponse += piece;
            if (onToken) {
                onToken(piece);
            }
        }

        if (stopFlag.load()) break;

        llama_batch step_batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, step_batch) != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("Итоговый ответ модели: %s", fullResponse.c_str());
    return fullResponse;
}


bool LlamaEngine::loadModel(const std::string& modelPath) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (model) {
        llama_model_free(model);
        model = nullptr;
    }

    llama_model_params model_params = llama_model_default_params();
    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model) {
        LOGE("Не удалось загрузить LLM модель из: %s", modelPath.c_str());
        return false;
    }

    resetContextInternal();
    if (!ctx) {
        LOGE("Не удалось создать контекст LLM");
        return false;
    }

    LOGI("LLM модель успешно загружена!");
    return true;
}

void LlamaEngine::clearHistory() {
    stopFlag.store(true);
    // Ждем завершения активного шага генерации, прежде чем чистить память (предотвращает вылет!)
    std::lock_guard<std::mutex> lock(engineMutex);
    resetContextInternal();
    LOGI("Контекст диалога безопасно очищен.");
}

void LlamaEngine::stopGeneration() {
    stopFlag.store(true);
}

