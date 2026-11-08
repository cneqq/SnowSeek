#include <jni.h>
#include <android/log.h>

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char *kTag = "SnowSeekLlama";
constexpr int32_t kMaxGeneratedTokens = 384;
constexpr int32_t kBatchTokens = 512;

struct ModelSession {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    int32_t contextTokens = 0;
    std::atomic<bool> cancelled{false};
    std::mutex generationMutex;

    ~ModelSession() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

std::once_flag gBackendInit;
std::atomic<jlong> gNextHandle{1};
std::mutex gSessionsMutex;
std::unordered_map<jlong, std::shared_ptr<ModelSession>> gSessions;

void throwJava(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    const jsize length = env->GetStringLength(value);
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (codepoint <= 0x7F) {
            output.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FF) {
            output.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0xFFFF) {
            output.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            output.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return output;
}

jstring fromUtf8(JNIEnv *env, const std::string &value, size_t length) {
    std::vector<jchar> output;
    output.reserve(length);
    for (size_t i = 0; i < length;) {
        const auto lead = static_cast<uint8_t>(value[i]);
        uint32_t codepoint = 0;
        size_t count = 1;
        if (lead < 0x80) {
            codepoint = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            codepoint = lead & 0x1F;
            count = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            codepoint = lead & 0x0F;
            count = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            codepoint = lead & 0x07;
            count = 4;
        } else {
            output.push_back(0xFFFD);
            ++i;
            continue;
        }
        if (i + count > length) break;
        bool valid = true;
        for (size_t j = 1; j < count; ++j) {
            const auto next = static_cast<uint8_t>(value[i + j]);
            if ((next & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        if (!valid || codepoint > 0x10FFFF || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
            output.push_back(0xFFFD);
            ++i;
            continue;
        }
        if (codepoint <= 0xFFFF) {
            output.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            output.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
            output.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        }
        i += count;
    }
    const jchar *chars = output.empty() ? nullptr : output.data();
    return env->NewString(chars, static_cast<jsize>(output.size()));
}

std::shared_ptr<ModelSession> getSession(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(gSessionsMutex);
    const auto found = gSessions.find(handle);
    if (found == gSessions.end()) {
        throwJava(env, "Сессия локальной модели уже закрыта");
        return nullptr;
    }
    return found->second;
}

bool shouldAbort(void *data) {
    return static_cast<ModelSession *>(data)->cancelled.load(std::memory_order_relaxed);
}

void logToAndroid(enum ggml_log_level level, const char *message, void *) {
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
        : level == GGML_LOG_LEVEL_INFO ? ANDROID_LOG_INFO
        : ANDROID_LOG_DEBUG;
    __android_log_write(priority, kTag, message);
}

void decodeBatch(ModelSession &session, llama_batch_ext *batch, const std::vector<llama_token> &tokens,
                 int32_t start, int32_t count) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[static_cast<size_t>(start + i)]);
        if (index < 0) throw std::runtime_error("Не удалось сформировать пакет токенов GGUF");
        const llama_pos position = start + i;
        if (!llama_batch_ext_set_pos(batch, index, &position)) {
            throw std::runtime_error("Модель отклонила позицию токена");
        }
        if (i == count - 1) llama_batch_ext_set_output_logits(batch, index, true);
    }
    const int32_t status = llama_process(session.context, LLAMA_PROCESS_TYPE_DECODE, batch);
    if (status != 0 && !session.cancelled.load(std::memory_order_relaxed)) {
        throw std::runtime_error("Не удалось обработать токены модели");
    }
}

void emitToken(JNIEnv *env, jobject sink, jmethodID onToken, const std::string &bytes, std::string &pending) {
    pending += bytes;
    size_t complete = 0;
    while (complete < pending.size()) {
        const auto lead = static_cast<uint8_t>(pending[complete]);
        size_t width = lead < 0x80 ? 1 : (lead & 0xE0) == 0xC0 ? 2 : (lead & 0xF0) == 0xE0 ? 3 : (lead & 0xF8) == 0xF0 ? 4 : 1;
        if (complete + width > pending.size()) break;
        complete += width;
    }
    if (complete == 0) return;
    jstring piece = fromUtf8(env, pending, complete);
    if (piece != nullptr) {
        env->CallVoidMethod(sink, onToken, piece);
        env->DeleteLocalRef(piece);
    }
    pending.erase(0, complete);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_snowseek_app_local_LlamaCppEngine_nativeLoad(JNIEnv *env, jobject, jstring modelPath, jint contextTokens) {
    try {
        const std::string path = toUtf8(env, modelPath);
        if (path.empty()) throw std::runtime_error("Путь к файлу модели пуст");
        if (contextTokens < 512 || contextTokens > 8192) throw std::runtime_error("Размер контекста вне поддерживаемого диапазона");
        std::call_once(gBackendInit, [] {
            llama_log_set(logToAndroid, nullptr);
            llama_backend_init();
        });

        auto session = std::make_shared<ModelSession>();
        session->contextTokens = contextTokens;
        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        session->model = llama_model_load_from_file(path.c_str(), modelParams);
        if (session->model == nullptr) throw std::runtime_error("Файл не является поддерживаемой моделью GGUF");
        if (llama_model_chat_template(session->model, nullptr) == nullptr) {
            throw std::runtime_error("В модели GGUF нет шаблона диалога");
        }

        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(contextTokens);
        contextParams.n_batch = static_cast<uint32_t>(std::min(contextTokens, kBatchTokens));
        contextParams.n_ubatch = contextParams.n_batch;
        const int threads = static_cast<int>(std::max(1u, std::min(4u, std::thread::hardware_concurrency())));
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        session->context = llama_init_from_model(session->model, contextParams);
        if (session->context == nullptr) throw std::runtime_error("Недостаточно памяти для контекста модели");
        llama_set_abort_callback(session->context, shouldAbort, session.get());

        const jlong handle = gNextHandle.fetch_add(1, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(gSessionsMutex);
        gSessions.emplace(handle, std::move(session));
        return handle;
    } catch (const std::exception &failure) {
        throwJava(env, failure.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_snowseek_app_local_LlamaCppEngine_nativeGenerate(JNIEnv *env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents, jobject sink) {
    auto session = getSession(env, handle);
    if (!session || env->ExceptionCheck()) return;
    std::unique_lock<std::mutex> generationLock(session->generationMutex);
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(sinkClass, "onToken", "(Ljava/lang/String;)V");
    if (onToken == nullptr) return;

    try {
        const jsize messageCount = env->GetArrayLength(roles);
        if (messageCount == 0 || messageCount != env->GetArrayLength(contents)) {
            throw std::runtime_error("Список сообщений модели некорректен");
        }
        std::vector<std::string> roleStorage;
        std::vector<std::string> contentStorage;
        roleStorage.reserve(static_cast<size_t>(messageCount));
        contentStorage.reserve(static_cast<size_t>(messageCount));
        for (jsize i = 0; i < messageCount; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
            roleStorage.push_back(toUtf8(env, role));
            contentStorage.push_back(toUtf8(env, content));
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
        }

        std::vector<llama_chat_message> chat;
        chat.reserve(static_cast<size_t>(messageCount));
        size_t messageChars = 0;
        for (jsize i = 0; i < messageCount; ++i) {
            chat.push_back({roleStorage[static_cast<size_t>(i)].c_str(), contentStorage[static_cast<size_t>(i)].c_str()});
            messageChars += contentStorage[static_cast<size_t>(i)].size() + roleStorage[static_cast<size_t>(i)].size();
        }

        const char *templateText = llama_model_chat_template(session->model, nullptr);
        std::vector<char> prompt(std::max<size_t>(1024, messageChars * 2 + 256));
        int32_t promptLength = llama_chat_apply_template(templateText, chat.data(), chat.size(), true,
                                                          prompt.data(), static_cast<int32_t>(prompt.size()));
        if (promptLength < 0) throw std::runtime_error("Шаблон диалога GGUF не поддерживается");
        if (static_cast<size_t>(promptLength) >= prompt.size()) {
            prompt.resize(static_cast<size_t>(promptLength) + 1);
            promptLength = llama_chat_apply_template(templateText, chat.data(), chat.size(), true,
                                                      prompt.data(), static_cast<int32_t>(prompt.size()));
        }
        if (promptLength <= 0) throw std::runtime_error("Не удалось подготовить запрос для модели GGUF");

        const llama_vocab *vocab = llama_model_get_vocab(session->model);
        int32_t tokenCount = -llama_tokenize(vocab, prompt.data(), promptLength, nullptr, 0, true, true);
        if (tokenCount <= 0) throw std::runtime_error("Не удалось разбить запрос на токены");
        std::vector<llama_token> promptTokens(static_cast<size_t>(tokenCount));
        tokenCount = llama_tokenize(vocab, prompt.data(), promptLength, promptTokens.data(), tokenCount, true, true);
        if (tokenCount <= 0) throw std::runtime_error("Не удалось прочитать токены запроса");
        promptTokens.resize(static_cast<size_t>(tokenCount));
        if (tokenCount + kMaxGeneratedTokens > session->contextTokens) {
            throw std::runtime_error("Диалог слишком длинный для контекста этой модели");
        }

        session->cancelled.store(false, std::memory_order_relaxed);
        llama_memory_clear(llama_get_memory(session->context), false);
        llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
        llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
        if (sampler == nullptr) throw std::runtime_error("Не удалось создать настройки генерации");
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        llama_batch_ext *batch = llama_batch_ext_init(session->context);
        if (batch == nullptr) {
            llama_sampler_free(sampler);
            throw std::runtime_error("Не удалось создать пакет токенов");
        }

        try {
            for (int32_t start = 0; start < tokenCount; start += kBatchTokens) {
                if (session->cancelled.load(std::memory_order_relaxed)) break;
                decodeBatch(*session, batch, promptTokens, start, std::min(kBatchTokens, tokenCount - start));
            }

            std::string pendingUtf8;
            for (int32_t index = 0; index < kMaxGeneratedTokens && !session->cancelled.load(std::memory_order_relaxed); ++index) {
                const llama_token token = llama_sampler_sample(sampler, session->context, -1);
                if (llama_vocab_is_eog(vocab, token)) break;
                char tokenBytes[256];
                int32_t byteCount = llama_token_to_piece(vocab, token, tokenBytes, sizeof(tokenBytes), 0, false);
                if (byteCount < 0) {
                    std::vector<char> larger(static_cast<size_t>(-byteCount));
                    byteCount = llama_token_to_piece(vocab, token, larger.data(), static_cast<int32_t>(larger.size()), 0, false);
                    if (byteCount < 0) throw std::runtime_error("Не удалось декодировать токен модели");
                    emitToken(env, sink, onToken, std::string(larger.data(), static_cast<size_t>(byteCount)), pendingUtf8);
                } else {
                    emitToken(env, sink, onToken, std::string(tokenBytes, static_cast<size_t>(byteCount)), pendingUtf8);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    session->cancelled.store(true, std::memory_order_relaxed);
                    break;
                }
                if (pendingUtf8.size() > 4) throw std::runtime_error("Модель вернула некорректный UTF-8");

                const std::vector<llama_token> next{token};
                if (session->cancelled.load(std::memory_order_relaxed)) break;
                decodeBatch(*session, batch, next, tokenCount + index, 1);
            }
            llama_batch_ext_free(batch);
            llama_sampler_free(sampler);
        } catch (...) {
            llama_batch_ext_free(batch);
            llama_sampler_free(sampler);
            throw;
        }
    } catch (const std::exception &failure) {
        throwJava(env, failure.what());
    }
    env->DeleteLocalRef(sinkClass);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_snowseek_app_local_LlamaCppEngine_nativeCancel(JNIEnv *env, jobject, jlong handle) {
    std::shared_ptr<ModelSession> session;
    {
        std::lock_guard<std::mutex> lock(gSessionsMutex);
        const auto found = gSessions.find(handle);
        if (found == gSessions.end()) return;
        session = found->second;
    }
    session->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_snowseek_app_local_LlamaCppEngine_nativeClose(JNIEnv *env, jobject, jlong handle) {
    std::shared_ptr<ModelSession> session;
    {
        std::lock_guard<std::mutex> lock(gSessionsMutex);
        auto found = gSessions.find(handle);
        if (found == gSessions.end()) return;
        session = std::move(found->second);
        gSessions.erase(found);
    }
    session->cancelled.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> generationLock(session->generationMutex);
}
