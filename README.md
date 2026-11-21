# LocalVoice Headset — fully offline Android voice assistant

Headset-button variant of LocalVoice: wake/capture → VAD → on-device ASR → local GGUF LLM → Piper TTS. It reacts to wired/Bluetooth headset media buttons, boot and headset-connect events, and can reuse models already installed by the main app. The app has **no `INTERNET` permission**, no analytics, no account, and no cloud fallback.

Package: `com.localvoice.assistant.headset` (installable side-by-side with `com.localvoice.assistant`).

## Requirements

- Android 9+ (`minSdk 28`), `arm64-v8a` only. Target SDK is deliberately kept at 30.
- Galaxy A50 / A14-class device is the reference target; peak RSS is designed for about 2.2 GB.
- Models are supplied by the user; they are not bundled in the APK.

## Model layout

Preferred: this app's external files dir (`Android/data/com.localvoice.assistant.headset/files/models/`). If empty, the app falls back to the main app's `com.localvoice.assistant/files/models/`.

```
models/
  llm/    *.gguf                         # 1.5B default, 0.5B fast option
  vad/    silero_vad.onnx                # required; ten-vad*.onnx optional
  stt/    tokens.txt                     # required
          preprocess*.onnx, encode*.onnx, cached_decode*.onnx, uncached_decode*.onnx   # Moonshine path
          encoder*.onnx, decoder*.onnx, joiner*.onnx                                   # Zipformer fallback
  tts/    *.onnx, tokens.txt             # Piper voice, e.g. en_US-amy-low.onnx
          lexicon.txt, espeak-ng-data/   # optional
```

A GGUF picked via the in-app file picker gets a persistable read grant and survives process restarts. Non-seekable providers fall back to copying into `models/llm/`.

## Build

```sh
./gradlew assembleDebug --stacktrace
./gradlew lintDebug
```

`lintDebug` is expected to fail only on `ExpiredTargetSdkVersion`; `targetSdk = 30` is intentional. `MissingApplicationIcon` is a known headset lint issue.

## Verification checklist

```sh
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a'
"<ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump" -d \
  app/build/intermediates/cxx/Release/*/obj/arm64-v8a/libllama_jni.so \
  | grep -E 'sdot|udot|smmla|ummla|usmmla'
```

Expect only arm64 `.so` entries and zero forbidden instructions. No source or merged manifest should contain `android.permission.INTERNET`. Device smoke must use `PKG=com.localvoice.assistant.headset`, never the main package.

## Known limitations

- Headset button, boot and connect events start the foreground service automatically; media-button routing is OEM-sensitive.
- Time-to-first-audio on the 1.5B model is high; use the 0.5B GGUF for faster replies.
- Memory pressure can unload the LLM; reload from the UI afterward.
- No text-input fallback when speech models are unavailable.
- Play distribution is not targeted while `targetSdk = 30`.

## Licensing

There is no top-level project `LICENSE` file yet. Before redistribution, review upstream terms for each component and model:

- llama.cpp / ggml: https://github.com/ggml-org/llama.cpp
- sherpa-onnx and ONNX Runtime: https://github.com/k2-fsa/sherpa-onnx
- Piper engine and voice licenses: https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/LICENSING.md
- VAD models: https://huggingface.co/TEN-framework/ten-vad
- GGUF model license: check the model card for the exact GGUF you install.
