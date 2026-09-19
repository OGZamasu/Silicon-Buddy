// The JNI bridge between Silicon Buddy and llama.cpp.
//
// Deliberately small. It loads a GGUF, renders a conversation through the model's own chat
// template (llama.cpp's Jinja engine, with thinking off unless asked), streams the answer
// back as UTF-8 bytes, and stops the moment it is asked to — between tokens, and inside a
// token too, through llama.cpp's abort callback. Everything about *when* to do any of that
// (memory, heat, the app leaving the screen) is decided in Kotlin.
//
// Threading: every call on a session is made from one dedicated thread in Kotlin
// (`LlamaThread`), except `nativeCancel` and `nativeSetThreads`, which only touch atomics
// and may come from anywhere.
//
// Privacy: nothing the owner typed or the model wrote is ever logged. Only llama.cpp's own
// warnings and errors reach logcat, and those are about files and memory, not words.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"

namespace {

constexpr const char * TAG = "BuddyLlama";

// Status codes nativeGenerate returns; mirrored in LlamaNative.kt.
constexpr int STATUS_END_OF_TURN = 0;   // the model said it was done
constexpr int STATUS_LENGTH = 1;        // it reached maxTokens, or the context
constexpr int STATUS_STOP_STRING = 2;   // the template's own stop string appeared
constexpr int STATUS_CANCELLED = 3;     // cancelled by the app
constexpr int STATUS_PROMPT_TOO_LONG = -2;

// Indices into the metrics array nativeGenerate fills.
constexpr int METRIC_PROMPT_TOKENS = 0;
constexpr int METRIC_PROMPT_EVALUATED = 1;
constexpr int METRIC_GENERATED = 2;
constexpr int METRIC_PROMPT_MICROS = 3;
constexpr int METRIC_GENERATE_MICROS = 4;
constexpr int METRIC_CONTEXT = 5;
constexpr int METRIC_DROPPED_MESSAGES = 6;
constexpr int METRIC_FIRST_TOKEN_MICROS = 7;
constexpr int METRIC_COUNT = 8;

// ---- Logging ------------------------------------------------------------------------

std::mutex g_log_mutex;
std::string g_cpu_library;  // which CPU variant llama.cpp chose, from its own log line

void on_log(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    static const char * marker = "loaded CPU backend from ";
    if (const char * at = std::strstr(text, marker)) {
        std::string path(at + std::strlen(marker));
        while (!path.empty() && (path.back() == '\n' || path.back() == '\r')) path.pop_back();
        const auto slash = path.find_last_of('/');
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_cpu_library = slash == std::string::npos ? path : path.substr(slash + 1);
    }
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        default: return;
    }
    __android_log_write(priority, TAG, text);
}

// ---- JNI helpers -----------------------------------------------------------------------

void throw_state(JNIEnv * env, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string from_bytes(JNIEnv * env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    }
    return out;
}

std::string from_string(JNIEnv * env, jstring text) {
    if (text == nullptr) return {};
    const char * chars = env->GetStringUTFChars(text, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(text, chars);
    return out;
}

std::string basename_of(const char * path) {
    if (path == nullptr) return {};
    const char * slash = std::strrchr(path, '/');
    return slash != nullptr ? std::string(slash + 1) : std::string(path);
}

// How many leading bytes of `text` are whole UTF-8 characters. A token can end in the
// middle of one — an emoji is often two tokens — and Kotlin should only ever be handed
// text that decodes.
size_t whole_utf8(const std::string & text) {
    const size_t size = text.size();
    for (size_t back = 0; back < 4 && back < size; back++) {
        const auto c = static_cast<unsigned char>(text[size - 1 - back]);
        if ((c & 0xC0) == 0x80) continue;  // a continuation byte: keep looking for its lead
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back + 1 < need ? size - back - 1 : size;
    }
    return size;
}

std::string describe_backends() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (!out.empty()) out += "; ";
        const std::string name = ggml_backend_reg_name(reg);
        out += name;
        void * features = ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
        std::string library;
        if (features != nullptr) {
            Dl_info info{};
            if (dladdr(features, &info) != 0) library = basename_of(info.dli_fname);
        }
        if (library.empty() && name == "CPU") {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            library = g_cpu_library;
        }
        if (!library.empty()) out += " " + library;
        if (features != nullptr) {
            auto get = reinterpret_cast<ggml_backend_get_features_t>(features);
            std::string flags;
            for (ggml_backend_feature * f = get(reg); f != nullptr && f->name != nullptr; f++) {
                const std::string value = f->value != nullptr ? f->value : "";
                if (value == "0") continue;
                if (!flags.empty()) flags += ",";
                flags += f->name;
                if (!value.empty() && value != "1") flags += "=" + value;
            }
            if (!flags.empty()) out += " [" + flags + "]";
        }
    }
    return out;
}

// ---- A loaded model --------------------------------------------------------------------

struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr templates;
    // What the context's memory holds, in order, so the next turn can skip what is
    // already there instead of reading the whole conversation again.
    std::vector<llama_token> cached;
    std::atomic<bool> cancel{false};
    std::atomic<int> threads_prompt{4};
    std::atomic<int> threads_generate{4};
    int applied_prompt = 0;
    int applied_generate = 0;
    int n_ctx = 0;
    int n_batch = 512;
    // Whether the memory can be cut back to a shared prefix. Recurrent and hybrid models
    // (Qwen3.5 is one) keep a running state that cannot be rewound, and a sliding-window
    // cache may already have dropped what a cut would need; for those only a pure
    // append is reused.
    bool can_trim = false;

    ~Session() {
        templates.reset();
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

bool abort_requested(void * data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

std::atomic<bool> g_load_cancel{false};

bool load_progress(float, void *) {
    return !g_load_cancel.load(std::memory_order_relaxed);
}

void apply_threads(Session & session) {
    const int prompt = session.threads_prompt.load(std::memory_order_relaxed);
    const int generate = session.threads_generate.load(std::memory_order_relaxed);
    if (prompt != session.applied_prompt || generate != session.applied_generate) {
        llama_set_n_threads(session.context, generate, prompt);
        session.applied_prompt = prompt;
        session.applied_generate = generate;
    }
}

void forget_memory(Session & session) {
    llama_memory_clear(llama_get_memory(session.context), false);
    session.cached.clear();
}

std::vector<common_chat_msg> read_messages(JNIEnv * env, jobjectArray roles, jobjectArray contents) {
    std::vector<common_chat_msg> messages;
    const jsize count = roles != nullptr ? env->GetArrayLength(roles) : 0;
    for (jsize i = 0; i < count; i++) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        common_chat_msg message;
        message.role = from_string(env, role);
        message.content = from_bytes(env, content);
        messages.push_back(std::move(message));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    return messages;
}

common_chat_params render(const common_chat_templates * templates, const std::vector<common_chat_msg> & messages,
                          bool thinking) {
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    // llama.cpp's default here is true, and Qwen3.5's template thinks only when it is:
    // off means passing false, not leaving it out.
    inputs.enable_thinking = thinking;
    return common_chat_templates_apply(templates, inputs);
}

int64_t micros_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now() - start)
        .count();
}

}  // namespace

// ---- Entry points ------------------------------------------------------------------------
//
// Named for dev.siliconoptimizer.buddy.llama.LlamaNative, which R8 is told to keep by name
// (consumer-rules.pro). Renamed, every one of these would be an UnsatisfiedLinkError.

extern "C" JNIEXPORT jstring JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeInit(JNIEnv * env, jclass, jstring library_dir) {
    static std::once_flag once;
    const std::string directory = from_string(env, library_dir);
    std::call_once(once, [&directory] {
        llama_log_set(on_log, nullptr);
        // Every libggml-cpu-*.so in the app's native library folder is asked what it can
        // run on this CPU, and the best scorer is kept.
        ggml_backend_load_all_from_path(directory.c_str());
        llama_backend_init();
    });
    const std::string backends = describe_backends();
    __android_log_print(ANDROID_LOG_INFO, TAG, "backends: %s", backends.c_str());
    return env->NewStringUTF(backends.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeBackends(JNIEnv * env, jclass) {
    return env->NewStringUTF(describe_backends().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeLoad(
    JNIEnv * env, jclass, jstring model_path, jint context_length, jint batch,
    jint threads_prompt, jint threads_generate) {
    const std::string path = from_string(env, model_path);
    g_load_cancel.store(false);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.progress_callback = load_progress;

    auto session = std::make_unique<Session>();
    session->model = llama_model_load_from_file(path.c_str(), model_params);
    if (session->model == nullptr) {
        throw_state(env, g_load_cancel.load() ? "cancelled" : "llama.cpp could not load that model file");
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);

    int size = context_length > 0 ? context_length : 4096;
    const int trained = llama_model_n_ctx_train(session->model);
    if (trained > 0 && size > trained) size = trained;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(size);
    context_params.n_batch = static_cast<uint32_t>(batch > 0 ? batch : 512);
    context_params.n_ubatch = context_params.n_batch;
    context_params.n_seq_max = 1;
    context_params.n_threads = std::max(1, static_cast<int>(threads_generate));
    context_params.n_threads_batch = std::max(1, static_cast<int>(threads_prompt));
    context_params.abort_callback = abort_requested;
    context_params.abort_callback_data = session.get();
    context_params.no_perf = true;

    session->context = llama_init_from_model(session->model, context_params);
    if (session->context == nullptr) {
        throw_state(env, "llama.cpp could not make room for a context of that size");
        return 0;
    }
    session->n_ctx = static_cast<int>(llama_n_ctx(session->context));
    session->n_batch = static_cast<int>(context_params.n_batch);
    session->threads_prompt = context_params.n_threads_batch;
    session->threads_generate = context_params.n_threads;
    session->applied_prompt = context_params.n_threads_batch;
    session->applied_generate = context_params.n_threads;
    session->can_trim = !llama_model_is_recurrent(session->model) &&
                        !llama_model_is_hybrid(session->model) &&
                        llama_model_n_swa(session->model) == 0;
    try {
        session->templates = common_chat_templates_init(session->model, "");
    } catch (const std::exception & error) {
        // A model with no usable template can still complete raw text; a conversation
        // will be refused with a sentence rather than a crash.
        __android_log_print(ANDROID_LOG_WARN, TAG, "no chat template: %s", error.what());
    }
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeCancelLoad(JNIEnv *, jclass) {
    g_load_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeReset(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Session *>(handle)->cancel.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Session *>(handle)->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeSetThreads(
    JNIEnv *, jclass, jlong handle, jint prompt, jint generate) {
    if (handle == 0) return;
    auto * session = reinterpret_cast<Session *>(handle);
    session->threads_prompt.store(std::max(1, static_cast<int>(prompt)));
    session->threads_generate.store(std::max(1, static_cast<int>(generate)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeUnload(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<Session *>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeGenerate(
    JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents,
    jbyteArray raw_prompt, jboolean thinking, jint max_tokens, jfloat temperature, jint top_k,
    jfloat top_p, jfloat min_p, jint seed, jobject sink, jlongArray metrics_out) {
    if (handle == 0) {
        throw_state(env, "no model is loaded");
        return -1;
    }
    Session & session = *reinterpret_cast<Session *>(handle);
    jlong metrics[METRIC_COUNT] = {0};
    metrics[METRIC_CONTEXT] = session.n_ctx;

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_text = env->GetMethodID(sink_class, "onText", "([B)Z");
    if (on_text == nullptr) return -1;  // NoSuchMethodError is pending

    // ---- The prompt: a conversation through the chat template, or raw text.
    std::vector<std::string> stops;
    std::vector<llama_token> tokens;
    const int reserve = std::min(std::max(1, static_cast<int>(max_tokens)), 512);
    if (raw_prompt != nullptr) {
        tokens = common_tokenize(session.vocab, from_bytes(env, raw_prompt), true, true);
    } else {
        if (!session.templates) {
            throw_state(env, "this model has no chat template");
            return -1;
        }
        std::vector<common_chat_msg> messages = read_messages(env, roles, contents);
        // The oldest turns go first when the conversation outgrows the context, keeping a
        // system message and always the newest message.
        while (true) {
            common_chat_params rendered;
            try {
                rendered = render(session.templates.get(), messages, thinking == JNI_TRUE);
            } catch (const std::exception & error) {
                throw_state(env, std::string("the chat template failed: ") + error.what());
                return -1;
            }
            tokens = common_tokenize(session.vocab, rendered.prompt, true, true);
            stops = rendered.additional_stops;
            if (static_cast<int>(tokens.size()) + reserve < session.n_ctx) break;
            auto oldest = std::find_if(messages.begin(), messages.end() - 1,
                                       [](const common_chat_msg & m) { return m.role != "system"; });
            if (messages.size() <= 1 || oldest == messages.end() - 1) break;
            messages.erase(oldest);
            metrics[METRIC_DROPPED_MESSAGES]++;
        }
    }
    metrics[METRIC_PROMPT_TOKENS] = static_cast<jlong>(tokens.size());
    if (tokens.empty() || static_cast<int>(tokens.size()) + 1 >= session.n_ctx) {
        env->SetLongArrayRegion(metrics_out, 0, METRIC_COUNT, metrics);
        return STATUS_PROMPT_TOO_LONG;
    }
    const int limit = std::min(static_cast<int>(max_tokens), session.n_ctx - static_cast<int>(tokens.size()) - 1);

    // ---- What is already in memory.
    llama_memory_t memory = llama_get_memory(session.context);
    size_t reuse = 0;
    while (reuse < session.cached.size() && reuse < tokens.size() && session.cached[reuse] == tokens[reuse]) {
        reuse++;
    }
    if (reuse >= tokens.size()) reuse = tokens.size() - 1;  // at least one token to decode
    bool reused = false;
    if (reuse > 0) {
        if (reuse == session.cached.size()) {
            reused = true;  // a pure append is valid for every kind of memory
        } else if (session.can_trim && llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(reuse), -1)) {
            reused = true;
        }
    }
    if (!reused) {
        forget_memory(session);
        reuse = 0;
    }
    session.cached.resize(reuse);
    metrics[METRIC_PROMPT_EVALUATED] = static_cast<jlong>(tokens.size() - reuse);

    // ---- Sampling.
    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> chain(
        llama_sampler_chain_init(chain_params), llama_sampler_free);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain.get(), llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(chain.get(), llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(chain.get(), llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(chain.get(), llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    // ---- Reading the prompt.
    const auto started = std::chrono::steady_clock::now();
    apply_threads(session);
    for (size_t at = reuse; at < tokens.size(); at += static_cast<size_t>(session.n_batch)) {
        const auto n = static_cast<int32_t>(std::min(tokens.size() - at, static_cast<size_t>(session.n_batch)));
        const int rc = llama_decode(session.context, llama_batch_get_one(tokens.data() + at, n));
        if (rc == 2 || session.cancel.load()) {
            forget_memory(session);
            metrics[METRIC_PROMPT_MICROS] = micros_since(started);
            env->SetLongArrayRegion(metrics_out, 0, METRIC_COUNT, metrics);
            return STATUS_CANCELLED;
        }
        if (rc != 0) {
            forget_memory(session);
            throw_state(env, "llama.cpp could not read the prompt (" + std::to_string(rc) + ")");
            return -1;
        }
        session.cached.insert(session.cached.end(), tokens.begin() + static_cast<long>(at),
                              tokens.begin() + static_cast<long>(at) + n);
        apply_threads(session);
    }
    metrics[METRIC_PROMPT_MICROS] = micros_since(started);

    // ---- Writing the answer.
    const auto writing = std::chrono::steady_clock::now();
    std::string pending;  // bytes not yet handed to Kotlin
    int status = STATUS_LENGTH;
    int generated = 0;
    bool failed = false;
    auto hand_over = [&](size_t count) -> bool {
        if (count == 0) return true;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(count));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte *>(pending.data()));
        env->CallBooleanMethod(sink, on_text, bytes);
        env->DeleteLocalRef(bytes);
        pending.erase(0, count);
        if (metrics[METRIC_FIRST_TOKEN_MICROS] == 0) metrics[METRIC_FIRST_TOKEN_MICROS] = micros_since(started);
        return !env->ExceptionCheck();
    };

    while (generated < limit) {
        if (session.cancel.load()) {
            status = STATUS_CANCELLED;
            break;
        }
        apply_threads(session);
        llama_token token = llama_sampler_sample(chain.get(), session.context, -1);
        if (llama_vocab_is_eog(session.vocab, token)) {
            status = STATUS_END_OF_TURN;
            break;
        }
        pending += common_token_to_piece(session.vocab, token, false);

        // A stop string the template asked for ends the answer where it starts.
        size_t cut = std::string::npos;
        for (const auto & stop : stops) {
            if (stop.empty()) continue;
            const size_t at = pending.find(stop);
            if (at != std::string::npos) cut = std::min(cut, at);
        }
        if (cut != std::string::npos) {
            pending.resize(cut);
            status = STATUS_STOP_STRING;
            generated++;
            break;
        }
        // Hold back whatever could be the start of one, and any half-written character.
        size_t hold = 0;
        for (const auto & stop : stops) {
            if (stop.empty()) continue;
            for (size_t k = std::min(stop.size() - 1, pending.size()); k > hold; k--) {
                if (pending.compare(pending.size() - k, k, stop, 0, k) == 0) {
                    hold = k;
                    break;
                }
            }
        }
        const size_t ready = whole_utf8(pending.substr(0, pending.size() - hold));
        if (!hand_over(ready)) {
            failed = true;
            break;
        }

        const int rc = llama_decode(session.context, llama_batch_get_one(&token, 1));
        if (rc == 2 || session.cancel.load()) {
            status = STATUS_CANCELLED;
            generated++;
            break;
        }
        if (rc != 0) {
            forget_memory(session);
            throw_state(env, "llama.cpp could not continue the answer (" + std::to_string(rc) + ")");
            return -1;
        }
        session.cached.push_back(token);
        generated++;
    }
    if (failed) {
        forget_memory(session);
        return -1;  // the sink threw; its exception is pending
    }
    if (status == STATUS_CANCELLED) {
        // Whatever a cancelled decode left behind is not worth trusting next turn.
        forget_memory(session);
    }
    hand_over(pending.size());
    if (env->ExceptionCheck()) return -1;

    metrics[METRIC_GENERATED] = generated;
    metrics[METRIC_GENERATE_MICROS] = micros_since(writing);
    env->SetLongArrayRegion(metrics_out, 0, METRIC_COUNT, metrics);
    return status;
}

// The prompt a conversation renders to, from the model's vocabulary alone — no weights, so
// it costs megabytes where a load costs gigabytes. What the chat sends a model is exactly
// this, which is how a test sees the template's thinking switch on a model too big to run.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeRenderPrompt(
    JNIEnv * env, jclass, jstring model_path, jobjectArray roles, jobjectArray contents, jboolean thinking) {
    const std::string path = from_string(env, model_path);
    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        throw_state(env, "llama.cpp could not read that model's vocabulary");
        return nullptr;
    }
    std::string prompt;
    try {
        common_chat_templates_ptr templates = common_chat_templates_init(model, "");
        prompt = render(templates.get(), read_messages(env, roles, contents), thinking == JNI_TRUE).prompt;
    } catch (const std::exception & error) {
        llama_model_free(model);
        throw_state(env, std::string("the chat template failed: ") + error.what());
        return nullptr;
    }
    llama_model_free(model);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(prompt.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(prompt.size()), reinterpret_cast<const jbyte *>(prompt.data()));
    return out;
}
