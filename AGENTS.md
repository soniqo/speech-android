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

## Tests

### Android (emulator or device)

```bash
./gradlew :sdk:connectedAndroidTest
```

Models download automatically via `ModelManager.ensureModels()`.
23 tests across 5 suites: SileroVadTest, ParakeetSttTest, KokoroTtsTest, PipelineE2ETest, BargeInTest.

### Linux

```bash
# 1. Download ONNX Runtime
linux/setup_linux.sh

# 2. Download test models
linux/tests/download_models.sh

# 3. Build
cd linux && cmake -B build -DORT_DIR=../ort-linux && cmake --build build

# 4. Run (set model dir)
SPEECH_MODEL_DIR=tests/models ./build/speech_test
```

11 tests: config, lifecycle, speech detection, concurrency, null safety.

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

## Workflow

- **Never push directly to main.** Create a feature branch, open a PR, and merge after review.
- Branch naming: `feat/description`, `fix/description`, `chore/description`
- PRs should include: summary, test plan, and link to related issues
- Tag releases from main after PR is merged: `git tag v0.0.X && git push origin v0.0.X`
- CI runs on tags: builds SDK, runs unit tests, publishes to Maven Central + GitHub Packages, creates GitHub Release with APK

## Testing

### Unit tests (no device needed)

```bash
./gradlew :sdk:test
```

15 tests: download retry, resume, timeout, validation, edge cases.

### E2E tests (arm64 emulator or device)

```bash
./gradlew :sdk:connectedAndroidTest
```

31 tests across 7 suites: SileroVadTest, ParakeetSttTest, KokoroTtsTest, KokoroMultilingualTest, PipelineE2ETest, BargeInTest, DeepFilterTest.

#### Emulator setup (arm64, 4GB RAM required)

```bash
sdkmanager "system-images;android-35-ext14;google_apis_playstore;arm64-v8a"
echo "no" | avdmanager create avd -n speech_test -k "system-images;android-35-ext14;google_apis_playstore;arm64-v8a" -d pixel_6
# Edit ~/.android/avd/speech_test.avd/config.ini → hw.ramSize=4096
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd speech_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 4096
```

Models (~1.2GB) download on first run. Subsequent runs use cache.

### Linux

```bash
linux/setup_linux.sh
linux/tests/download_models.sh
cd linux && cmake -B build -DORT_DIR=../ort-linux && cmake --build build
SPEECH_MODEL_DIR=tests/models ./build/speech_test
```

11 tests: config, lifecycle, speech detection, concurrency, null safety.

## Guidelines

- Keep native code in C++17, no external deps beyond ONNX Runtime, OkHttp, and speech-core
- Kotlin SDK should be minimal — thin wrapper over JNI
- All model tensor names/shapes must match actual ONNX exports
- Test on arm64-v8a (Snapdragon) as primary target
- No Claude attribution in commits, PRs, or model cards
- **Never push directly to main — always use a PR**
- **Always ask for confirmation before creating a git commit**
- **Always ask for confirmation before any action visible to others** — pushing to any branch, opening / commenting on / reviewing / closing / merging PRs or issues, posting to Slack or any external service. The git commit rule above is one instance of this broader principle: never create externally visible artifacts without explicit confirmation.
- **Run unit tests (`./gradlew :sdk:test`) after making code changes**
- **Run e2e tests (`./gradlew :sdk:connectedAndroidTest`) before tagging a release**
- **README translations must stay in sync.** Any change to `README.md` must be mirrored in all translated copies: `README_zh.md`, `README_ja.md`, `README_ko.md`, `README_es.md`, `README_de.md`, `README_fr.md`, `README_hi.md`, `README_pt.md`, `README_ru.md`
