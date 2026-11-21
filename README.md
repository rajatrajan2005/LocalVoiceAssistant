# LocalVoice — fully offline Android voice assistant

A local-first voice assistant for arm64 Android: wake/capture → VAD → on-device ASR → local GGUF LLM → Piper TTS. The app has **no `INTERNET` permission**, no analytics, no account, and no cloud fallback.

Two variants live in separate repositories:

- `LocalVoiceAssistant` — main app, package `com.localvoice.assistant`
- `LocalVoiceAssistant-Headset` — headset-button app, package `com.localvoice.assistant.headset`

## Requirements

- Android 9+ (`minSdk 28`), `arm64-v8a` only. Target SDK is deliberately kept at 30.
- A Galaxy A50 / A14-class device is the reference target. Peak memory is designed for about 2.2 GB RSS.
- Models are supplied by the user; they are not bundled in the APK.

## Model layout

Place models in external app storage (`Android/data/<package>/files/models/`):

```
models/
  llm/    *.gguf                         # e.g. Qwen2.5-1.5B-Instruct Q4_K_M (1.5B default), 0.5B fast option
  vad/    silero_vad.onnx                # required; ten-vad*.onnx optional
  stt/    tokens.txt                     # required
          preprocess*.onnx, encode*.onnx, cached_decode*.onnx, uncached_decode*.onnx   # Moonshine path
          encoder*.onnx, decoder*.onnx, joiner*.onnx                                   # Zipformer fallback
  tts/    *.onnx, tokens.txt             # Piper voice, e.g. en_US-amy-low.onnx
          lexicon.txt, espeak-ng-data/   # optional
```

Use the in-app file picker to select a GGUF directly from any provider. A persistable read grant is requested, so a picked model survives process restarts. If the provider is not seekable, the model is copied into `models/llm/`. Files with multiple matches are chosen in sorted filename order; keep one model per directory to avoid ambiguity.

## Build

```sh
./gradlew assembleDebug --stacktrace
./gradlew lintDebug
```

`lintDebug` is expected to fail only on `ExpiredTargetSdkVersion`; `targetSdk = 30` is intentional.

## Verification checklist

```sh
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a'
"<ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump" -d \
  app/build/intermediates/cxx/Release/*/obj/arm64-v8a/libllama_jni.so \
  | grep -E 'sdot|udot|smmla|ummla|usmmla'
```

Expect only arm64 `.so` entries and zero forbidden instructions. No source or merged manifest should contain `android.permission.INTERNET`.

## Known limitations

- Time-to-first-audio is currently well above the aspirational target on the 1.5B model; the 0.5B GGUF gives faster replies at lower quality.
- Memory pressure can unload the LLM; reload from the UI afterward.
- No text-input fallback when speech models are unavailable.
- Tool output is parsed from few-shot JSON; complex requests may produce an "invalid reply" message.
- Play distribution is not targeted while `targetSdk = 30`; this is a sideload/hobby build.

## Licensing

There is no top-level project `LICENSE` file yet. Before redistribution, review the upstream terms for each component and model:

- llama.cpp / ggml: https://github.com/ggml-org/llama.cpp
- sherpa-onnx and ONNX Runtime: https://github.com/k2-fsa/sherpa-onnx
- Piper engine and voice licenses (voices differ from the engine): https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/LICENSING.md
- VAD models (Silero / TEN-VAD): https://huggingface.co/TEN-framework/ten-vad
- GGUF model license: check the model card for the exact GGUF you install.
