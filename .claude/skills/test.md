---
name: test
description: Run all test suites (Android emulator + Linux)
user_invocable: true
---

Run the project's test suites. Execute each step and report results.

## Android Tests

1. Check if an emulator is running: `adb devices | grep -w device`
2. If yes, run: `./gradlew :sdk:connectedAndroidTest`
3. If no emulator, report "Skipped — no emulator running"

## Linux Tests

1. If `linux/tests/models/silero-vad.onnx` doesn't exist, download models:
   ```
   linux/tests/download_models.sh
   ```
2. If `linux/build/speech_test` doesn't exist, build:
   ```
   cd linux && cmake -B build -DORT_DIR=../ort-linux && cmake --build build
   ```
   If `ort-linux/` doesn't exist, run `linux/setup_linux.sh` first.
3. Run tests:
   ```
   SPEECH_MODEL_DIR=linux/tests/models DYLD_LIBRARY_PATH=ort-linux/lib linux/build/speech_test
   ```

## Report

Summarize: total tests, passed, failed, skipped for each suite.
