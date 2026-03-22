# Speech Android

On-device speech SDK for Android, powered by [ONNX Runtime](https://onnxruntime.ai) and [speech-core](https://github.com/soniqo/speech-core).

Speech recognition, text-to-speech, voice activity detection, and noise cancellation — all running locally on Android with NNAPI acceleration for Qualcomm Snapdragon and Samsung Exynos. No cloud APIs, no data leaves the device.

**[Models](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple counterpart) · **[speech-core](https://github.com/soniqo/speech-core)** (pipeline engine)

## Models

| Model | Task | INT8 Size | Latency | Languages |
|-------|------|-----------|---------|-----------|
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Voice activity detection | 2 MB | <1 ms / chunk | Any |
| [Parakeet TDT 0.6B](https://huggingface.co/aufklarer/Parakeet-TDT-0.6B-ONNX) | Speech recognition | 661 MB | ~300 ms / utterance | 25 European |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Text-to-speech | 134 MB | ~200 ms / sentence | 8 |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Noise cancellation | ~8 MB | Real-time | Any |

Models are downloaded automatically on first launch via `ModelManager`.

## Quick Start

### 1. Add dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("audio.soniqo:speech:0.1.0")
}
```

### 2. Download models

```kotlin
val modelDir = ModelManager.ensureModels(context) { progress ->
    Log.d("Speech", "Downloading ${progress.file}... ${progress.completed}/${progress.totalFiles}")
}
```

### 3. Create pipeline

```kotlin
val pipeline = SpeechPipeline(
    SpeechConfig(
        modelDir = modelDir,
        useNnapi = true,        // Qualcomm / Samsung NPU
        precision = ModelPrecision.INT8,
    )
)

// Listen for events
lifecycleScope.launch {
    pipeline.events.collect { event ->
        when (event) {
            is SpeechEvent.SpeechStarted -> { /* user started talking */ }
            is SpeechEvent.TranscriptionCompleted -> {
                Log.d("Speech", "STT: ${event.text} (${event.sttMs.toInt()}ms)")
            }
            is SpeechEvent.ResponseAudioDelta -> { /* TTS audio chunk */ }
            is SpeechEvent.ResponseDone -> pipeline.resumeListening()
            else -> {}
        }
    }
}

pipeline.start()
```

### 4. Feed microphone audio

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    16000, // 16 kHz
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_FLOAT,
    bufferSize,
)

audioRecord.startRecording()
val buffer = FloatArray(512) // 32ms chunks
while (running) {
    audioRecord.read(buffer, 0, 512, AudioRecord.READ_BLOCKING)
    pipeline.pushAudio(buffer)
}
```

### 5. Cleanup

```kotlin
pipeline.stop()
pipeline.close()
```

## Pipeline

The speech pipeline is a state machine driven by voice activity detection:

```
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Events are emitted as a Kotlin `SharedFlow<SpeechEvent>`:

| Event | When |
|-------|------|
| `SpeechStarted` | VAD detects speech onset |
| `SpeechEnded` | VAD detects silence after speech |
| `PartialTranscription` | Streaming STT partial result |
| `TranscriptionCompleted` | Final transcription with confidence and latency |
| `ResponseCreated` | TTS synthesis started |
| `ResponseAudioDelta` | TTS audio chunk (PCM16 bytes) |
| `ResponseDone` | TTS synthesis complete |
| `ResponseInterrupted` | User interrupted (barge-in) |
| `Error` | Pipeline error |

## Architecture

```
┌──────────────────────────────────────────────┐
│              SpeechPipeline (Kotlin)          │
│         events: SharedFlow<SpeechEvent>       │
└──────────────────┬───────────────────────────┘
                   │ JNI
┌──────────────────┴───────────────────────────┐
│            speech-core (C++ submodule)        │
│   Turn detection · Interruption handling      │
│   Conversation context · Tool calling         │
└──┬────────┬────────┬────────┬────────────────┘
   │        │        │        │  vtables
┌──┴──┐  ┌──┴──┐  ┌──┴──┐  ┌─┴────────┐
│ VAD │  │ STT │  │ TTS │  │ Enhancer │
│Silero│  │Para-│  │Koko-│  │DeepFilter│
│     │  │keet │  │ro   │  │Net3      │
└──┬──┘  └──┬──┘  └──┬──┘  └─┬────────┘
   │        │        │        │
   └────────┴────────┴────────┘
         ONNX Runtime + NNAPI
```

## Build from Source

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh          # Downloads ONNX Runtime + inits speech-core submodule
./gradlew :app:assembleDebug
```

### Run tests

```bash
./gradlew :sdk:connectedDebugAndroidTest
```

18 e2e tests covering VAD, STT, TTS, noise cancellation, pipeline lifecycle, and concurrency.

## Hardware Acceleration

| Chipset | Acceleration | EP |
|---------|-------------|-----|
| Qualcomm Snapdragon 8 Gen 1+ | Hexagon NPU | NNAPI |
| Samsung Exynos 2200+ | Samsung NPU | NNAPI |
| Google Tensor G2+ | Google TPU | NNAPI |
| MediaTek Dimensity 9000+ | APU | NNAPI |
| Other | CPU (XNNPACK) | Default |

NNAPI is enabled by default via `SpeechConfig(useNnapi = true)`.

## Configuration

```kotlin
SpeechConfig(
    modelDir = modelDir,         // Path to downloaded models
    useNnapi = true,             // Hardware acceleration
    enableEnhancer = true,       // Noise cancellation before VAD
    precision = ModelPrecision.INT8,  // INT8 (default) or FP32
)
```

## Related

| Repository | Platform | Description |
|-----------|----------|-------------|
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) | MLX + CoreML inference |
| [speech-core](https://github.com/soniqo/speech-core) | Cross-platform | C++ pipeline engine |
| **speech-android** | Android | ONNX Runtime inference |

## License

Apache 2.0
