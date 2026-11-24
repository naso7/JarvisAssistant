//
// Created by Naso on 12.11.2026.
//

#include "llama_engine.h"
#include <android/log.h>

#define LOG_TAG "JarvisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

LlamaEngine::LlamaEngine() {
    llama_backend_init();
}

LlamaEngine::~LlamaEngine() {
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    llama_backend_free();
}

void LlamaEngine::resetContext() {
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model) {
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 2048; // Для мобильного телефона 2048 токенов оптимально по RAM
        ctx_params.n_threads = 4;
        ctx = llama_init_from_model(model, ctx_params);
    }
    global_pos = 0;
    isFirstTurn = true;
}

bool LlamaEngine::loadModel(const std::string& modelPath) {
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

    resetContext();
    if (!ctx) {
        LOGE("Не удалось создать контекст LLM");
        return false;
    }

    LOGI("LLM модель успешно загружена!");
    return true;
}

void LlamaEngine::clearHistory() {
    stopFlag.store(true);
    resetContext();
}

void LlamaEngine::stopGeneration() {
    stopFlag.store(true);
}

std::string LlamaEngine::generateResponse(const std::string& userMessage, TokenCallback onToken) {
    if (!ctx || !model) return "";

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) return "";

    stopFlag.store(false);

    // Формируем системный промпт Джарвиса на первом шаге
    std::string prompt;
    if (isFirstTurn) {
        prompt += "<|im_start|>system\n"
                  "Ты — Джарвис, голосовой ИИ-помощник из фильма Железный человек. "
                  "Обращайся к пользователю 'сэр' или 'юный сэр', отвечай кратко (1-2 предложения), "
                  "вежливо и с легким британским юмором. "
                  "Если просят включить фонарик, добавь в конце ответа тег [ACTION:FLASHLIGHT_ON]. "
                  "Если выключить фонарик — [ACTION:FLASHLIGHT_OFF]. "
                  "Если поставить таймер (например на 5 минут) — [ACTION:TIMER:5].<|im_end|>\n";
        isFirstTurn = false;
    }

    prompt += "<|im_start|>user\n" + userMessage + "<|im_end|>\n<|im_start|>assistant\n";

    std::vector<llama_token> tokens(prompt.size() + 4);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), false, true);
    if (n_tokens < 0) return "";
    tokens.resize(n_tokens);

    // Обработка входного промпта
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = global_pos + i;
        batch.n_seq_id[i] = 1;
        if (batch.seq_id && batch.seq_id[i]) {
            batch.seq_id[i][0] = 0;
        }
        batch.logits[i]   = false;
    }
    batch.logits[tokens.size() - 1] = true;
    batch.n_tokens = tokens.size();
    global_pos += tokens.size();

    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        return "";
    }
    int first_step_idx = batch.n_tokens - 1;
    llama_batch_free(batch);

    // Настройка сэмплера (температура 0.5 для живого, но точного общения)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string fullResponse;
    const int max_tokens = 250; // Для голосового помощника длинные монологи не нужны

    for (int i = 0; i < max_tokens; ++i) {
        if (stopFlag.load()) break;

        int logits_idx = (i == 0) ? first_step_idx : 0;
        llama_token id = llama_sampler_sample(smpl, ctx, logits_idx);

        if (id == llama_vocab_eos(vocab)) break;

        std::vector<char> buf(64, '\0');
        int n = llama_token_to_piece(vocab, id, buf.data(), buf.size(), 0, true);
        if (n > 0) {
            std::string piece(buf.data(), n);
            if (piece.find("<|im_") == std::string::npos && piece.find("|>") == std::string::npos) {
                fullResponse += piece;
                if (onToken) {
                    onToken(piece); // Отдаем слово в Kotlin в реальном времени
                }
            }
        }

        if (stopFlag.load()) break;

        llama_batch step_batch = llama_batch_init(1, 0, 1);
        step_batch.token[0]    = id;
        step_batch.pos[0]      = global_pos;
        step_batch.n_seq_id[0] = 1;
        if (step_batch.seq_id && step_batch.seq_id[0]) {
            step_batch.seq_id[0][0] = 0; // Двойной индекс для двумерного массива!
        }
        step_batch.logits[0]   = true;
        step_batch.n_tokens    = 1;
        global_pos++;

        int res = llama_decode(ctx, step_batch);
        llama_batch_free(step_batch);
        if (res != 0) {
            LOGE("Ошибка llama_decode на шаге генерации: %d", res);
            break;
        }
    }

    llama_sampler_free(smpl);
    return fullResponse;
}
