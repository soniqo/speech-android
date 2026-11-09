# Agent Instructions

## Project

speech-android — on-device speech SDK for Android (VAD + STT + TTS + noise cancellation).

Thin Kotlin SDK + JNI bridge over the [speech-core](https://github.com/soniqo/speech-core)
C++ engine, which provides the orchestration pipeline AND the ONNX Runtime
model wrappers (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3). This
repo owns only the Android packaging and a single ~250-line JNI bridge.

Linux/automotive support moved to [speech-core's `examples/linux/`](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Structure

- `speech-core/` — git submodule (do not modify directly; open PRs against soniqo/speech-core)
- `sdk/src/main/cpp/` — `jni_bridge.cpp` + `CMakeLists.txt`. That's it. All model code lives in speech-core.
- `sdk/src/main/kotlin/audio/soniqo/speech/` — Kotlin public SDK
- `sdk/src/androidTest/` — instrumented e2e tests
- `app/` — demo application
- `control-demo/` — separate full-pipeline voice-command demo (VAD → STT → FunctionGemma → Android tools → TTS)
- `setup.sh` — downloads ONNX Runtime, initializes the speech-core submodule

## Build

```bash
./setup.sh
./gradlew :app:assembleDebug
./gradlew :control-demo:assembleDebug
./gradlew :sdk:connectedAndroidTest
```

## Tests

### Unit tests (no device needed)

```bash
./gradlew :sdk:test
./gradlew :control-demo:testDebugUnitTest
```

Download retry / resume / timeout / validation / edge cases.

### E2E tests (arm64 emulator or device)

```bash
./gradlew :sdk:connectedAndroidTest
```

Suites: `SileroVadTest`, `ParakeetSttTest`, `KokoroTtsTest`,
`KokoroMultilingualTest`, `PipelineE2ETest`, `BargeInTest`, `DeepFilterTest`,
`StreamingTranscriberTest`, `SpeakerDiarizerTest`, `SpeakerEmbedderTest`.

Models (~1.2GB) download on first run via `ModelManager.ensureModels()`.
Subsequent runs use the device-side cache.

#### Emulator setup (arm64, 4GB RAM required)

```bash
sdkmanager "system-images;android-35-ext14;google_apis_playstore;arm64-v8a"
echo "no" | avdmanager create avd -n speech_test -k "system-images;android-35-ext14;google_apis_playstore;arm64-v8a" -d pixel_6
# Edit ~/.android/avd/speech_test.avd/config.ini → hw.ramSize=4096
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd speech_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 4096
```

## Models

ONNX models hosted on HuggingFace under [`aufklarer/`](https://huggingface.co/aufklarer)
org. INT8 quantized by default.

- `soniqo/Silero-VAD-v5-ONNX` — VAD
- `soniqo/Parakeet-TDT-v3-ONNX` — STT (25 European languages, 8192 BPE vocab)
- `soniqo/Kokoro-82M-ONNX` — TTS + phonemizer dicts + voice embeddings
- `soniqo/Pocket-TTS-100M-ONNX-INT8` — streaming English TTS, fixed Alba voice
- `soniqo/DeepFilterNet3-ONNX` — noise enhancer
- `soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8` — multilingual streaming STT, in the pipeline or as `StreamingTranscriber`
- `soniqo/Sortformer-Diarization-4spk-ONNX` — streaming speaker diarization (`SpeakerDiarizer`)
- `soniqo/ReDimNet2-B6-ONNX-FP32` — speaker embeddings (`SpeakerEmbedder`)

`ModelManager.kt` handles download and caching. See speech-core's
[`docs/models.md`](https://github.com/soniqo/speech-core/blob/main/docs/models.md)
for the full model-file inventory.

## Key files

- `sdk/src/main/cpp/jni_bridge.cpp` — constructs `speech_core::SileroVad`/`ParakeetStt` and the selected Kokoro/Pocket TTS wrapper, then feeds them to `speech_core::VoicePipeline`. No vtable adapters — the model wrappers implement the interfaces directly.
- `sdk/src/main/cpp/CMakeLists.txt` — pulls speech-core in via `add_subdirectory` with `SPEECH_CORE_WITH_ONNX=ON`; the speech_core_models target provides every model wrapper.
- `sdk/src/main/kotlin/audio/soniqo/speech/SpeechPipeline.kt` — main public Kotlin API.
- `sdk/src/main/kotlin/audio/soniqo/speech/NativeBridge.kt` — JNI surface (must stay in lockstep with `jni_bridge.cpp`).
- `sdk/src/main/kotlin/audio/soniqo/speech/VadDetector.kt` — VAD-only public API (Silero + speech-core `TurnDetector`, no pipeline). The listening counterpart to `SpeechSynthesizer`.
- `sdk/src/main/kotlin/audio/soniqo/speech/{StreamingTranscriber,SpeakerDiarizer,SpeakerEmbedder}.kt` — standalone meeting-transcription models with no pipeline: a Nemotron multilingual stream, streaming Sortformer and ReDimNet2-B6. They return text, per-frame probabilities and vectors only; thresholds, turns, speaker labels and voice matching belong to the app, not this SDK.
- `ParakeetTranscriber.kt` — standalone Parakeet TDT v3 INT8 batch API, bounded to 30 seconds per call, with decoder emission word times. Call once per app-owned utterance, never once per microphone callback. Its immutable, SHA-256-verified model cache and optional worker phase are separate from Nemotron and pipeline caches; it has no fixed-language prompt.
- `sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt` — model download + caching. Profiles: full pipeline (`models/`), TTS-only (`models_tts/`), VAD-only (`models_vad/`), plus the standalone `models_transcriber-*`, `models_diarizer/` and `models_speaker_embedding/` sets. `ModelManager.endpoint` overrides the download origin for every set, for a mirror the user explicitly chose.

Native code that used to live here (`models/*.{cpp,h}`, `audio/{fft,mel,stft}.cpp`,
`util/json.h`, `onnx_engine.h`) is now under speech-core. Modify it via a
speech-core PR, then bump the submodule pointer here.

## Workflow

- **Never push directly to main.** Create a feature branch, open a PR, merge after review.
- Branch naming: `feat/description`, `fix/description`, `chore/description`.
- PRs should include: summary, test plan, and link to related issues.
- Tag releases from main after merge: `git tag v0.0.X && git push origin v0.0.X`.
- CI runs on tags: builds SDK, runs unit tests, publishes to Maven Central + GitHub Packages, creates GitHub Release with APK.

## Guidelines

- Keep native code in C++17. No external deps beyond ONNX Runtime, OkHttp, and speech-core.
- Kotlin SDK stays minimal — thin wrapper over JNI.
- All model tensor names/shapes must match the published ONNX exports under `aufklarer/`.
- Test on arm64-v8a (Snapdragon) as primary target.
- Keep `control-demo` routing model-driven: do not add keyword/regex command
  dispatch, and offer FunctionGemma only tools valid for the current device
  state.
- Keep `control-demo`'s `ControlTools` declarations and compact prompt compatible
  with the published Control adapter whenever the tool surface, argument schema,
  prompt serialization, or state rules change.
- **No Claude attribution** in commits, PRs, or model cards. Strip both the `🤖 Generated with [Claude Code]` footer and the `Co-Authored-By: Claude …` trailer from defaults.
- **Never push directly to main — always use a PR**.
- **Always ask for confirmation before creating a git commit**.
- **Always ask for confirmation before any externally-visible action** — pushing to any branch, opening / commenting on / reviewing / closing / merging PRs or issues, posting to Slack or any external service. The git commit rule above is one instance of this broader principle.
- **Run unit tests (`./gradlew :sdk:test`) after making code changes**.
- **Run e2e tests (`./gradlew :sdk:connectedAndroidTest`) before tagging a release**.
- **README translations must stay in sync.** Any change to `README.md` must be mirrored in all translated copies: `README_zh.md`, `README_ja.md`, `README_ko.md`, `README_es.md`, `README_de.md`, `README_fr.md`, `README_hi.md`, `README_pt.md`, `README_ru.md`.
