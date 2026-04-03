# Agent Instructions

## Project

speech-android — on-device speech SDK for Android and embedded Linux (VAD + STT + TTS + noise cancellation).

## Structure

- `speech-core/` — C++17 git submodule, pipeline orchestration (do not modify directly)
- `sdk/src/main/cpp/` — ONNX Runtime model implementations, JNI bridge, audio DSP
- `sdk/src/main/kotlin/com/soniqo/speech/` — Kotlin public SDK
- `sdk/src/androidTest/` — instrumented e2e tests
- `linux/` — embedded Linux C API (automotive/Yocto)
- `app/` — demo application
- `setup.sh` — downloads ONNX Runtime, initializes submodule

## Build

```bash
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest
```

## Models

ONNX models hosted on HuggingFace under `aufklarer/` org. INT8 is default.
Parakeet TDT v3 — multilingual STT (114 languages, 8192 BPE vocab).
ModelManager.kt handles download and caching.

## Key files

- `jni_bridge.cpp` — wires ONNX models to speech-core C API via vtables
- `SpeechPipeline.kt` — main public API
- `parakeet_stt.cpp` — STT with TDT greedy decoder + per-feature mel normalization
- `kokoro_tts.cpp` + `kokoro_phonemizer.cpp` — TTS with dictionary-based phonemizer
- `silero_vad.cpp` — voice activity detection
- `deepfilter.cpp` — noise cancellation with STFT/ERB processing
- `onnx_engine.h` — platform-aware ONNX Runtime wrapper (Android NNAPI / Linux QNN)
- `linux/src/speech.cpp` — Linux C API implementation
- `linux/include/speech.h` — Linux public C header

## Guidelines

- Keep native code in C++17, no external deps beyond ONNX Runtime and speech-core
- Kotlin SDK should be minimal — thin wrapper over JNI
- All model tensor names/shapes must match actual ONNX exports
- Test on arm64-v8a (Snapdragon) as primary target
- No Claude attribution in commits, PRs, or model cards
- **Always ask for confirmation before creating a git commit**
