# LocalVoice offline assistant optimization report

## Objective

Make the Samsung Galaxy A50 offline assistant faster and lighter without breaking the hard constraints: no network, arm64-v8a only, plain ARMv8-A CPU code, no `dotprod`/`i8mm`/fp16/Vulkan/OpenMP, and resident memory at or below 2.2 GB.

This report covers the current baseline, implemented quick wins, researched options, a measurement plan, and the bundled `OptimizationDemo` app.

## Device and runtime profile

- Device: Samsung Galaxy A50 `SM-A505F`, USB `RZ8M40YYQWE`.
- SoC: Exynos 9610, 4x Cortex-A73 + 4x Cortex-A53, ARMv8-A, no `dotprod`/`i8mm`.
- Android: API 30. `PerformanceHintManager` is API 31+, so it is not usable here.
- RAM observed while idle: about 2.1-2.2 GB free of 5.6 GB.
- Apps:
  - Main: `com.localvoice.assistant`, repo branch `main`.
  - Headset: `com.localvoice.assistant.headset`, repo branch `headset-button`.
- Models:
  - Default LLM: `qwen2.5-1.5b-instruct-q4_k_m.gguf`.
  - Alternate: `qwen2.5-0.5b-instruct-q4_k_m.gguf` in the main app.
  - STT: Moonshine int8.
  - VAD: `silero_vad.onnx`.
  - TTS: Piper `en_US-amy-low.onnx`.

## Observed baseline

These are device observations, not controlled benchmarks.

| Metric | Current observation |
| --- | --- |
| 1.5B Q4_K_M resident memory | ~1.5 GB |
| 0.5B Q4_K_M resident memory | ~0.9-1.1 GB |
| Decode speed, 1.5B | ~11-13 tok/s |
| First-token latency | ~2.6 s on a typical prompt |
| STT latency | ~0.6-1.4 s per utterance, highly phrase-length dependent |
| TTS | Piper offline works; answer text now reveals in sync with speech |
| Background execution | Foreground service stays alive; assistant answers are not cut off |

## Fixes already landed before this report

- Forced plain-text answers and removed tool false positives.
- Added endpointing fix for offline voice recognition.
- Added model selector and assist activation.
- Added headset-button variant.
- Added token-level prompt/prefix cache in native generation.
- Fixed `TtsCallback.java` JNI bridge for Piper.
- Fixed TTS/answer synchronization by moving the progress ticker off the single-thread TTS dispatcher, pre-registering sentence timelines, capturing the AudioTrack head once, and using monotonic word/sentence cursors.
- Added app logo and launcher icon.

## Quick wins implemented in this iteration

Applied to both main and headset apps.

| Change | Before | After | Why |
| --- | --- | --- | --- |
| LLM thread default | `LlamaEngine.MAX_THREADS` = 8 | `LlamaEngine.DEFAULT_THREADS` = 4 | A50 has 4 big cores; 8 threads oversubscribe and add scheduling overhead. |
| VAD trailing silence | `0.45f` | `0.32f` | Shorter end-of-speech delay with the existing 0.12 s minimum speech gate. |
| STT recognizer threads | 4 | 2 | Moonshine int8 examples and mobile reports favor fewer threads on 4-core mobile CPUs. |
| TTS AudioTrack mode | default | `PERFORMANCE_MODE_LOW_LATENCY` | Reduces playback buffer latency for Piper PCM float output. |

Files changed:

- `app/src/main/java/com/localvoice/assistant/llm/LlamaEngine.kt`
- `app/src/main/java/com/localvoice/assistant/service/AssistantService.kt`
- `app/src/main/java/com/localvoice/assistant/voice/AudioPipeline.kt`
- `app/src/main/java/com/localvoice/assistant/voice/TtsPlayer.kt`

The existing native prefix cache in `app/src/main/cpp/llama_jni.cpp` is retained unchanged.

## Research findings

### LLM / llama.cpp

- Plain ARMv8 without `dotprod` is the A50 reality. Reported community benchmarks show `Q4_0` can be about 11% faster than `Q4_K_M`, while `Q8_0` can be about 28% slower. IQ2/IQ3 can be slower still because of unpacking cost.
- `Q4_K_S` is a useful middle option between `Q4_0` speed and `Q4_K_M` quality.
- 2-4 threads are generally best on 4-core mobile CPUs. 8 threads can lose because of contention.
- `q8_0` KV cache can reduce memory materially at longer context, but the current 1024-token context already keeps KV small, so this is not the first move.
- Prompt caching is already implemented and should be extended from the system prefix to the full conversation prefix.
- A smaller model gives the largest latency win. `Qwen3 0.6B` and `LFM2.5 1.2B` are better latency-first candidates than a 1.5B model on this device.

### STT / VAD / TTS

- Moonshine Tiny int8 is already a good fit. Published benchmarks report RTF around 0.05 vs about 0.07 for Whisper Tiny.
- VAD endpointing at 0.32 s is a reasonable latency-first setting for short commands.
- STT thread count 2 is a sensible A/B candidate against 4.
- Piper likes multiple threads for synthesis; keep `numThreads = 4`.
- `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` is safe for `MODE_STREAM` PCM float playback.
- Warming up Moonshine and Piper before the first user turn should reduce first-use latency.

### Android platform

- Foreground service restrictions on API 30 are manageable with `startForeground()` and a visible notification.
- Audio focus should remain transient and ducking-based: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `setWillPauseWhenDucked(true)`. Do not attempt focus lock; it requires system signatures.
- `PerformanceHintManager` is unavailable on API 30. `PowerManager.getCurrentThermalStatus()` is available and can drive thread reduction later.
- Manual core pinning is not recommended on A50 until measured; it can reduce scheduler flexibility.

## Prioritized roadmap

### P0 - done now

1. Default to 4 LLM threads.
2. Reduce VAD trailing silence to 0.32 s.
3. Reduce STT threads to 2.
4. Enable low-latency AudioTrack mode.
5. Push both apps and verify on device.

### P1 - next, low risk / high information

1. A/B `Q4_0`, `Q4_K_S`, and `Q4_K_M` with the same prompts and same thread count.
2. Extend prompt cache from system prefix to full conversation prefix.
3. Add warm-up synthesis and warm-up recognition at app start.
4. Benchmark context 512 / 1024 / 2048. 1024 is the current default.
5. Benchmark `batch = 128` vs current `batch = 256` in `llama_jni.cpp`.
6. Store models in internal files dir where possible; SAF/mmap access can add latency.

### P2 - larger changes

1. Try `Qwen3 0.6B Q4_K_M` as a latency-first mode.
2. Try `LFM2.5 1.2B Q4_0` as a quality/latency balanced mode.
3. Add `q8_0` KV cache only if 2048-token context becomes the default.
4. Add thermal-aware thread scaling using API 30 thermal status.
5. Consider Oboe/AAudio only if AudioTrack remains a measurable bottleneck.

### Avoid

- `dotprod`, `i8mm`, fp16, Vulkan, OpenMP, or `GGML_NATIVE` on this device.
- Manual CPU affinity pinning until A/B data supports it.
- Raising `targetSdk` from 30 without an explicit compatibility review.

## Benchmark plan

Use the same 20 prompts for every configuration. Record median and p95.

| Parameter | A | B | C |
| --- | --- | --- | --- |
| Quant | Q4_K_M | Q4_K_S | Q4_0 |
| Threads | 2 | 4 | 8 |
| Context | 512 | 1024 | 2048 |
| VAD silence | 0.45 s | 0.32 s | - |
| STT threads | 2 | 4 | - |
| KV cache | f16 | q8_0 | - |

Acceptance targets:

- TTFA under 1.5 s for short prompts.
- Decode over 15 tok/s for 1.5B, or equivalent responsiveness with a smaller model.
- STT under 800 ms for short commands.
- Resident memory under 1.8 GB with headroom to the 2.2 GB hard cap.
- No audio focus loss, no answer truncation, no TTS/text desync after 10 consecutive turns.

## Demo app

Path: `OptimizationDemo/`

Package: `com.localvoice.optimizationdemo`

The demo is a standalone offline Compose app with three tabs:

1. **Sync** - speaks text with Android `TextToSpeech` and reveals the answer progressively, matching the behavior in the two assistant apps.
2. **Estimator** - compares model/quant/thread/context/KV presets using an explicitly labeled estimate, not a device benchmark. It prints estimated tok/s, resident memory, TTFA, and whether the 2.2 GB budget is met.
3. **Roadmap** - shows the P0/P1/P2 checklist and the avoid-list from this report.

Build and install:

```bash
cd OptimizationDemo
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.localvoice.optimizationdemo/.MainActivity
```

The demo has no `INTERNET` permission and is arm64-v8a only, matching the assistant apps.

## Sources

- llama.cpp: https://github.com/ggml-org/llama.cpp
- ARM CPU inference analysis: https://arxiv.org/html/2601.14277v1
- GGUF quantization comparison: https://bric.pe.kr/blog/gguf-quantization-showdown-q4-q5-iq4-2026
- llama.cpp Android performance notes: https://github.com/Siddhesh2377/llama.cpp-android/blob/re-write/docs/PERFORMANCE.md
- Offline STT benchmark: https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/
- Moonshine models: https://k2-fsa.github.io/sherpa/onnx/moonshine/models.html
- sherpa-onnx Kotlin recognizer API: https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt
- Moonshine v2 models: https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html
- Galaxy A50 hardware: https://www.gsmarena.com/samsung_galaxy_a50-9554.php
- Galaxy A50 overview: https://en.wikipedia.org/wiki/Samsung_Galaxy_A50
- Foreground service changes: https://developer.android.com/develop/background-work/services/fgs/changes
- Foreground service launch: https://developer.android.com/develop/background-work/services/fgs/launch
- Background start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Audio focus: https://developer.android.com/media/optimize/audio-focus
- `AudioFocusRequest`: https://developer.android.com/reference/android/media/AudioFocusRequest
- `AudioManager`: https://developer.android.com/reference/android/media/AudioManager
