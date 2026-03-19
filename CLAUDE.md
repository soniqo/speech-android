# speech-android

On-device speech SDK for Android — VAD, STT, TTS, noise cancellation.

## Architecture

```
speech-core (C++ submodule) — pipeline orchestration
└── speech-android — ONNX Runtime model implementations + Kotlin SDK
```

- `speech-core/` — git submodule, cross-platform voice pipeline (turn detection, interruption handling, conversation context)
- `sdk/src/main/cpp/` — C++ ONNX Runtime model implementations + JNI bridge
- `sdk/src/main/kotlin/` — Kotlin public API (`SpeechPipeline`, `SpeechConfig`, `SpeechEvent`)
- `app/` — demo app with microphone input

## Models (ONNX, hosted on HuggingFace aufklarer/)

| Model | Task | INT8 Size |
|-------|------|-----------|
| Silero VAD v5 | Voice activity detection | 2 MB |
| Parakeet TDT 0.6B | Speech recognition (25 langs) | 661 MB |
| Kokoro 82M | Text-to-speech (50+ voices) | 134 MB |
| DeepFilterNet3 | Noise cancellation | ~8 MB |

## Build

```bash
./setup.sh                           # Download ONNX Runtime + init submodule
./gradlew :app:assembleDebug         # Build
./gradlew :sdk:connectedAndroidTest  # Run e2e tests on device
```

## Key interfaces (from speech-core)

The C API vtables in `jni_bridge.cpp` connect ONNX models to speech-core:
- `sc_vad_vtable_t` → `SileroVad`
- `sc_stt_vtable_t` → `ParakeetStt`
- `sc_tts_vtable_t` → `KokoroTts`
- `sc_enhancer_vtable_t` → `DeepFilterEnhancer`

## Tensor names (verified against actual ONNX exports)

**Silero VAD**: input [1,512], state [2,1,128], sr (scalar int64) → output [1,1], stateN [2,1,128]
**Parakeet encoder**: audio_signal [B,128,T], length [B] → outputs [B,1024,T'], encoded_lengths [B]
**Parakeet decoder_joint**: encoder_outputs + targets + target_length + input_states_1/2 → outputs [1030] + output_states_1/2
**Kokoro**: tokens [1,N] int64, style [1,256], speed [1] → audio [T]

## Conventions

- INT8 models are the default (suffix `-int8` in filenames)
- FP32 variants available for accuracy-sensitive use cases
- No LLM in pipeline yet — runs in Echo mode (STT → TTS)
- Qualcomm NNAPI acceleration enabled by default
