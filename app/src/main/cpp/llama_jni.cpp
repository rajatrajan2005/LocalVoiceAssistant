#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "llama.h"
#include "ggml-cpu.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", __VA_ARGS__)

namespace {

constexpr int32_t kMaxThreads = 4; // Cap to Galaxy A50's 4 big Cortex-A73 cores.

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ggml_threadpool * threadpool = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> cancel_requested{false};
    std::atomic<int32_t> pending_threads{kMaxThreads};
    std::mutex op_mutex; // Serializes ctx usage while leaving cancel_requested lock-free.
    std::string cached_system_prompt;
    std::vector<llama_token> cached_prefix_tokens;
};

std::once_flag g_backend_once;

void ensure_backend_init() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
    });
}

bool abort_callback(void * data) {
    return static_cast<LlamaHandle *>(data)->cancel_requested.load(std::memory_order_relaxed);
}

int32_t clamp_threads(int32_t threads) {
    return std::max(1, std::min(threads, kMaxThreads));
}

std::array<int32_t, kMaxThreads> select_big_cores(int32_t threads) {
    std::array<int32_t, kMaxThreads> cores{};
    cores.fill(-1);

    std::vector<std::pair<long long, int32_t>> freqs;
    for (int32_t cpu = 0; cpu < 8; ++cpu) {
        char path[128];
        std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE * file = std::fopen(path, "r");
        if (!file) continue;
        long long freq = 0;
        if (std::fscanf(file, "%lld", &freq) == 1 && freq > 0) {
            freqs.emplace_back(freq, cpu);
        }
        std::fclose(file);
    }

    std::sort(freqs.begin(), freqs.end(), [](const auto & a, const auto & b) {
        return a.first > b.first;
    });

    const size_t count = static_cast<size_t>(threads);
    if (freqs.size() >= count) {
        for (size_t i = 0; i < count; ++i) cores[i] = freqs[i].second;
    } else {
        for (int32_t i = 0; i < threads; ++i) cores[i] = 4 + i;
    }
    return cores;
}

ggml_threadpool * create_big_core_threadpool(int32_t threads) {
    ggml_threadpool_params params = ggml_threadpool_params_default(threads);
    const auto cores = select_big_cores(threads);
    for (int32_t i = 0; i < threads; ++i) {
        const int32_t cpu = cores[i];
        if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
            params.cpumask[cpu] = true;
        }
    }
    params.strict_cpu = true;
    return ggml_threadpool_new(&params);
}

std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * raw = env->GetStringUTFChars(s, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(s, raw);
    return out;
}

std::string format_chat_prompt(
    const llama_model * model,
    const std::string & system_prompt,
    const std::string * user_prompt,
    bool add_ass
) {
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", system_prompt.c_str()});
    if (user_prompt) {
        msgs.push_back({"user", user_prompt->c_str()});
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        size_t est = system_prompt.size() + (user_prompt ? user_prompt->size() : 0);
        std::string buf(est * 2 + 128, '\0');
        int32_t needed = llama_chat_apply_template(
            tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), static_cast<int32_t>(buf.size())
        );
        if (needed > static_cast<int32_t>(buf.size())) {
            buf.resize(needed);
            needed = llama_chat_apply_template(
                tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), needed
            );
        }
        if (needed >= 0) {
            buf.resize(needed);
            return buf;
        }
    }

    // Fallback to ChatML if a GGUF omits or uses an unsupported chat template.
    std::string out = "<|im_start|>system\n" + system_prompt + "<|im_end|>\n";
    if (user_prompt) {
        out += "<|im_start|>user\n" + *user_prompt + "<|im_end|>\n<|im_start|>assistant\n";
    }
    return out;
}

std::vector<llama_token> tokenize_text(const llama_vocab * vocab, const std::string & text) {
    if (text.empty()) return {};
    int32_t count = -llama_tokenize(
        vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true
    );
    if (count <= 0) return {};

    std::vector<llama_token> tokens(count);
    int32_t written = llama_tokenize(
        vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), count, true, true
    );
    if (written < 0) return {};
    tokens.resize(written);
    return tokens;
}

bool decode_tokens(
    LlamaHandle * h,
    const llama_token * tokens,
    int32_t count,
    llama_pos start_pos,
    bool need_last_logits
) {
    if (count <= 0) return true;
    const int32_t batch_cap = static_cast<int32_t>(llama_n_batch(h->ctx));
    llama_batch batch = llama_batch_init(std::min(count, batch_cap), 0, 1);
    bool ok = true;

    for (int32_t offset = 0; offset < count; offset += batch_cap) {
        if (h->cancel_requested.load(std::memory_order_relaxed)) {
            ok = false;
            break;
        }
        const int32_t chunk = std::min(batch_cap, count - offset);
        const bool is_final_chunk = (offset + chunk == count);
        batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = start_pos + offset + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            // Compute logits only for the final prompt token to avoid wasted lm_head matmuls.
            batch.logits[i] = (need_last_logits && is_final_chunk && i == chunk - 1) ? 1 : 0;
        }
        if (llama_decode(h->ctx, batch) != 0) {
            ok = false;
            break;
        }
    }

    llama_batch_free(batch);
    return ok;
}

bool ensure_prefix_locked(LlamaHandle * h, const std::string & system_prompt) {
    if (system_prompt.empty()) return true;
    if (h->cached_system_prompt == system_prompt && !h->cached_prefix_tokens.empty()) {
        return true;
    }

    const int32_t threads = h->pending_threads.load(std::memory_order_relaxed);
    llama_set_n_threads(h->ctx, threads, threads);
    llama_memory_clear(llama_get_memory(h->ctx), true);
    h->cached_system_prompt.clear();
    h->cached_prefix_tokens.clear();

    std::string formatted = format_chat_prompt(h->model, system_prompt, nullptr, false);
    std::vector<llama_token> sys_tokens = tokenize_text(h->vocab, formatted);
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
    if (sys_tokens.empty() || static_cast<int32_t>(sys_tokens.size()) >= n_ctx - 8) {
        LOGE("System prompt too large or empty: %zu tokens", sys_tokens.size());
        return false;
    }

    if (!decode_tokens(h, sys_tokens.data(), static_cast<int32_t>(sys_tokens.size()), 0, false)) {
        llama_memory_clear(llama_get_memory(h->ctx), true);
        return false;
    }

    h->cached_system_prompt = system_prompt;
    h->cached_prefix_tokens = std::move(sys_tokens);
    return true;
}

jlongArray make_result(JNIEnv * env, jlong tokens, jlong prefill_ms, jlong decode_ms, bool cancelled) {
    jlong values[4] = {tokens, prefill_ms, decode_ms, cancelled ? 1LL : 0LL};
    jlongArray out = env->NewLongArray(4);
    if (out) {
        env->SetLongArrayRegion(out, 0, 4, values);
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeLoad(
    JNIEnv * env,
    jobject /* thiz */,
    jstring jpath,
    jint n_ctx,
    jint n_threads
) {
    ensure_backend_init();
    std::string path = jstring_to_std(env, jpath);
    if (path.empty()) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // Keep weights paged from flash under the 2.2 GB resident budget (use_mmap=true, use_mlock=false).

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("Failed to load model: %s", path.c_str());
        return 0;
    }

    const int32_t threads = clamp_threads(n_threads);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 1024;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        cparams = llama_context_default_params();
        cparams.n_ctx = 1024;
        cparams.n_batch = 512;
        cparams.n_ubatch = 512;
        cparams.n_threads = threads;
        cparams.n_threads_batch = threads;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, cparams);
    }
    if (!ctx) {
        LOGE("Failed to create llama_context");
        llama_model_free(model);
        return 0;
    }

    ggml_threadpool * threadpool = create_big_core_threadpool(threads);
    if (threadpool) {
        llama_attach_threadpool(ctx, threadpool, nullptr);
    }

    auto * h = new LlamaHandle();
    h->model = model;
    h->ctx = ctx;
    h->threadpool = threadpool;
    h->vocab = llama_model_get_vocab(model);
    h->pending_threads.store(threads, std::memory_order_relaxed);
    llama_set_abort_callback(ctx, abort_callback, h);

    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeUnload(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle_ptr
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h) return;
    h->cancel_requested.store(true, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(h->op_mutex);
        if (h->ctx && h->threadpool) llama_detach_threadpool(h->ctx);
        if (h->ctx) llama_free(h->ctx);
        if (h->threadpool) {
            ggml_threadpool_free(h->threadpool);
            h->threadpool = nullptr;
        }
        if (h->model) llama_model_free(h->model);
    }
    delete h;
}

JNIEXPORT void JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeSetThreads(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle_ptr,
    jint n_threads
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h) return;
    const int32_t threads = clamp_threads(n_threads);
    if (threads == h->pending_threads.load(std::memory_order_relaxed)) return;

    std::lock_guard<std::mutex> lock(h->op_mutex);
    if (h->ctx) {
        llama_set_n_threads(h->ctx, threads, threads);
        if (h->threadpool) {
            llama_detach_threadpool(h->ctx);
            ggml_threadpool_free(h->threadpool);
            h->threadpool = nullptr;
        }
        h->threadpool = create_big_core_threadpool(threads);
        if (h->threadpool) {
            llama_attach_threadpool(h->ctx, h->threadpool, nullptr);
        }
    }
    h->pending_threads.store(threads, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeCancel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle_ptr
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h) return;
    h->cancel_requested.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeClearCancel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle_ptr
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h) return;
    h->cancel_requested.store(false, std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeWarmPrefix(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle_ptr,
    jstring jsystem
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->op_mutex);
    std::string system_prompt = jstring_to_std(env, jsystem);
    return ensure_prefix_locked(h, system_prompt) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_localvoice_assistant_llm_LlamaEngine_nativeGenerate(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle_ptr,
    jstring jsystem,
    jstring juser,
    jstring jgrammar,
    jint max_tokens,
    jobject receiver
) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!h || !receiver) return make_result(env, 0, 0, 0, true);

    std::lock_guard<std::mutex> lock(h->op_mutex);

    const int32_t threads = h->pending_threads.load(std::memory_order_relaxed);
    llama_set_n_threads(h->ctx, threads, threads);

    std::string system_prompt = jstring_to_std(env, jsystem);
    std::string user_prompt = jstring_to_std(env, juser);
    std::string grammar = jstring_to_std(env, jgrammar);

    ensure_prefix_locked(h, system_prompt);

    std::string full_prompt = format_chat_prompt(h->model, system_prompt, &user_prompt, true);
    std::vector<llama_token> full_tokens = tokenize_text(h->vocab, full_prompt);
    const int32_t n_prompt = static_cast<int32_t>(full_tokens.size());
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));

    if (n_prompt <= 0 || n_prompt >= n_ctx - 4) {
        LOGE("Prompt length out of bounds for n_ctx=%d: %d", n_ctx, n_prompt);
        return make_result(env, 0, 0, 0, false);
    }

    // Keep at least 1 trailing token to decode so logits are populated for sampling.
    const size_t max_common = std::min(h->cached_prefix_tokens.size(), full_tokens.size() - 1);
    size_t n_common = 0;
    while (n_common < max_common && h->cached_prefix_tokens[n_common] == full_tokens[n_common]) {
        ++n_common;
    }

    llama_memory_t mem = llama_get_memory(h->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_common), -1)) {
        llama_memory_clear(mem, true);
        n_common = 0;
        h->cached_prefix_tokens.clear();
        h->cached_system_prompt.clear();
    }

    auto t_prefill_start = std::chrono::steady_clock::now();
    const int32_t suffix_len = n_prompt - static_cast<int32_t>(n_common);
    if (!decode_tokens(
            h,
            full_tokens.data() + n_common,
            suffix_len,
            static_cast<llama_pos>(n_common),
            true
        )) {
        return make_result(env, 0, 0, 0, h->cancel_requested.load(std::memory_order_relaxed));
    }
    auto t_prefill_end = std::chrono::steady_clock::now();
    const jlong prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        t_prefill_end - t_prefill_start
    ).count();

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (!grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
        if (!g) {
            LOGE("Failed to compile GBNF grammar");
            llama_sampler_free(smpl);
            return make_result(env, 0, prefill_ms, 0, false);
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    jclass receiver_cls = env->GetObjectClass(receiver);
    jmethodID on_token_mid = receiver_cls
        ? env->GetMethodID(receiver_cls, "onTokenBytes", "([B)V")
        : nullptr;
    if (receiver_cls) env->DeleteLocalRef(receiver_cls);
    if (!on_token_mid) {
        LOGE("Missing TokenReceiver.onTokenBytes([B)V");
        llama_sampler_free(smpl);
        return make_result(env, 0, prefill_ms, 0, false);
    }

    const int32_t max_gen = std::max<int32_t>(0, std::min<int32_t>(max_tokens, n_ctx - n_prompt - 1));
    llama_batch single = llama_batch_init(1, 0, 1);
    std::vector<char> piece_buf(256);
    int32_t n_generated = 0;
    llama_pos cur_pos = n_prompt;
    bool cancelled = false;

    auto t_decode_start = std::chrono::steady_clock::now();
    for (int32_t step = 0; step < max_gen; ++step) {
        if (h->cancel_requested.load(std::memory_order_relaxed)) {
            cancelled = true;
            break;
        }

        // llama_sampler_sample applies the chain and advances grammar state internally.
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) {
            break;
        }

        int32_t len = llama_token_to_piece(
            h->vocab, tok, piece_buf.data(), static_cast<int32_t>(piece_buf.size()), 0, false
        );
        if (len < 0) {
            piece_buf.resize(static_cast<size_t>(-len));
            len = llama_token_to_piece(
                h->vocab, tok, piece_buf.data(), static_cast<int32_t>(piece_buf.size()), 0, false
            );
        }

        if (len > 0) {
            // Emit raw bytes because NewStringUTF aborts on split multi-byte UTF-8 or 4-byte emoji.
            jbyteArray arr = env->NewByteArray(len);
            if (!arr) break;
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(piece_buf.data()));
            env->CallVoidMethod(receiver, on_token_mid, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                cancelled = true;
                break;
            }
        }

        ++n_generated;
        single.n_tokens = 1;
        single.token[0] = tok;
        single.pos[0] = cur_pos++;
        single.n_seq_id[0] = 1;
        single.seq_id[0][0] = 0;
        single.logits[0] = 1;

        if (llama_decode(h->ctx, single) != 0) {
            cancelled = h->cancel_requested.load(std::memory_order_relaxed);
            break;
        }
    }
    auto t_decode_end = std::chrono::steady_clock::now();

    llama_batch_free(single);
    llama_sampler_free(smpl);

    const jlong decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        t_decode_end - t_decode_start
    ).count();
    return make_result(env, n_generated, prefill_ms, decode_ms, cancelled);
}

} // extern "C"