# Speech Android

📖 Read in: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-device speech SDK for Android, powered by [ONNX Runtime](https://onnxruntime.ai) and [speech-core](https://github.com/soniqo/speech-core).

Low-memory streaming speech recognition (25 languages by default, 114-language TDT optional), text-to-speech, voice activity detection, and noise cancellation — all running locally. No cloud APIs, no data leaves the device.

**[📚 Android Documentation](https://soniqo.audio/getting-started/android)**

**[Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Models](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple counterpart) · **[speech-core](https://github.com/soniqo/speech-core)** (pipeline engine + Linux/embedded build)

## Demo

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="We fit a full offline voice agent into 1.2 GB on Android — watch the demo on YouTube">
  </a>
</p>
<p align="center"><em>The <a href="control-demo/">control-demo</a> command loop — Silero VAD → Parakeet STT → FunctionGemma → device action → Pocket TTS reply — fully offline in 1.2 GB of RAM</em></p>

## Scope

This repo is the **Android packaging**: Kotlin SDK, JNI bridge, demo app. The C++ engine and ONNX model wrappers (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) live in [speech-core](https://github.com/soniqo/speech-core) and are pulled in via a git submodule. Linux / automotive (Yocto, Qualcomm SA8295P/SA8255P) lives at [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Models

| Model | Task | Download | Peak memory | Languages |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/guides/dictate) | Streaming STT + end-of-utterance (default) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/guides/parakeet/android) | Broad-coverage STT (optional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 114 |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Offline STT + translation (optional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/guides/kokoro/android) | Text-to-speech (default) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Streaming text-to-speech (optional, fixed Alba voice) | ~126 MB | not yet measured | English |
| [Supertonic-3](https://soniqo.audio/guides/supertonic) | Text-to-speech (LiteRT, flow-matching, G2P-free, 44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/guides/vad/android) | Voice activity detection | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Any |
| [DeepFilterNet3](https://soniqo.audio/guides/denoise/android) | Noise cancellation | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | not loaded by default | Any |
| [FunctionGemma 270M](https://soniqo.audio/guides/function-calls) | On-device LLM — structured function / tool calls | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | app-runtime dependent | EN-tuned |

Models are downloaded automatically on first launch via `ModelManager.ensureModels()`.

`SpeechConfig()` defaults to `SttModel.PARAKEET_EOU` and `TtsModel.KOKORO_SHORT_TURN`
to keep SDK integrations and the system recognizer on the low-memory Android
path. The demo app opts into `SttModel.PARAKEET` so its echo and dictation
screens exercise the larger 114-language TDT model.

For language-focused recognition, use `SpeechConfig(sttModel = SttModel.PARAKEET,
languageHints = listOf("en", "fr"))`. Set `language = "en"` when you want a
single concrete language instead of a shortlist.

**Supertonic-3** is an opt-in higher-quality multilingual TTS — select it with
`SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requires the LiteRT backend). The host runs its four
non-autoregressive flow-matching graphs on-device at 44.1 kHz; the front-end is G2P-free (NFKD +
Unicode index — no phonemizer), so all 31 languages go through one path.

**FunctionGemma 270M** is a Gemma 3 derivative trained for structured tool
calls. The Kotlin wrapper (`audio.soniqo.speech.llm.FunctionGemma`) is a
runtime-agnostic shell: bring your own LiteRT-LM runtime adapter (see the
[Kotlin usage](#kotlin-usage) section) and the SDK handles prompt
formatting and call parsing. The model bundle ships as a single 283 MB
`.litertlm` file.

## Try the demo

Download the [signed APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) and install on any arm64 Android device (8+). The default low-memory model bundle (~500 MB) downloads automatically on first launch.

## Add dependency

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.15")
}
```

## Kotlin usage

```kotlin
val modelDir = ModelManager.ensureModels(context)

val pipeline = SpeechPipeline(
    SpeechConfig(modelDir = modelDir, useNnapi = false)
)

pipeline.events.collect { event ->
    when (event) {
        is SpeechEvent.TranscriptionCompleted -> println(event.text)
        is SpeechEvent.ResponseDone -> pipeline.resumeListening()
        else -> {}
    }
}

pipeline.start()

// Feed 16kHz mono float32 PCM from microphone
pipeline.pushAudio(samples)
```

### FunctionGemma 270M (on-device tool-calling LLM)

The SDK ships the prompt formatter (`FunctionGemmaPrompt`), parser
(`FunctionGemmaParser`) and a small façade (`FunctionGemma`). You bring
the LiteRT-LM runtime — e.g. the `com.google.ai.edge.litert:litert-lm-runtime`
Maven artifact — and adapt it to the one-method `FunctionGemma.Runtime`
interface so the SDK stays free of that transitive dependency.

```kotlin
import audio.soniqo.speech.llm.*

val runtime = object : FunctionGemma.Runtime {
    private val engine = /* load model.litertlm via your chosen runtime */
    override fun generate(prompt: String, maxNewTokens: Int): String =
        engine.generateResponse(prompt, maxNewTokens)
    override fun cancel() { engine.cancel() }
}

val llm = FunctionGemma(runtime)

val tools = listOf(
    FunctionDeclaration(
        name = "get_weather",
        description = "Get current weather",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "location" to mapOf("type" to "string"),
            ),
        ),
    ),
)

val rawResponse = llm.generateToolCall("What's the weather in Tokyo?", tools)
val calls = llm.parseToolCalls(rawResponse)
// -> [FunctionCall(name="get_weather",
//                  arguments={"location": ArgumentValue.Str("Tokyo")})]
```

The model bundle (`model.litertlm`, 283 MB) is published at
[soniqo/FunctionGemma-270M-LiteRT-LM](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM).

## Build from source

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 e2e tests
```

`./setup.sh` initializes the speech-core submodule and downloads ONNX Runtime
into `./ort/`.

## Demo app

The [`app/`](app/) module is a minimal voice assistant demo with:

- Real-time VAD waveform visualization
- Echo mode: transcribes speech and synthesizes it back (no LLM)
- Dictation mode: streaming partial results
- Voice overlay: a floating mic button that dictates into any app
- 114-language Parakeet TDT STT in the echo and dictation screens
- `SpeechRecognizer` test screen — exercises the system-wide voice input path
- Chat bubble UI with STT/TTS latency display

```bash
./gradlew :app:installDebug
```

### Voice overlay (dictate into any app)

**Voice overlay** puts a draggable mic button on top of other apps. Tap it and
it becomes **■ stop** / **✕ cancel**: stop types the transcript into whatever
text field currently has focus, cancel discards it. If no editable field is
focused, the text goes to the clipboard rather than being lost.

Three grants are needed, each with its own system screen — the setup screen
shows which are still missing:

| Permission | Why |
| --- | --- |
| Microphone | capture audio |
| Display over other apps | draw the button outside the app |
| Accessibility service | type into another app's text field |

The overlay window is deliberately non-focusable so the target field keeps
input focus while the buttons are tapped. Text is inserted at the cursor with
`ACTION_SET_TEXT`, falling back to clipboard paste for fields that reject it.

> Installing from an APK rather than the Play Store? Android blocks the
> accessibility toggle until you allow it under
> Settings → Apps → Speech → ⋮ → **Allow restricted settings**.

### Full-pipeline control demo

The separate [`control-demo/`](control-demo/) app runs the complete agent
locally: Silero VAD → Parakeet-EOU STT → FunctionGemma 270M tool calls →
Android device actions → Pocket TTS. It reports per-stage latency and links
directly to this checkout's `:sdk`, so local speech optimizations are used.

Download the [signed Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
from the latest release, or install a development build from source:

```bash
./gradlew :control-demo:installDebug
```

## System voice input (`RecognitionService`)

The SDK ships a ready-made `audio.soniqo.speech.service.SpeechRecognitionService`
that plugs into Android's framework `SpeechRecognizer` API — no code to write.
Once your app is selected as the default voice recognizer, any third-party app
calling `SpeechRecognizer.createSpeechRecognizer(context)` (with no
`ComponentName`) gets fully on-device STT through your pipeline.

**1. Declare `RECORD_AUDIO` and the service in `AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<application>
    <service
        android:name="audio.soniqo.speech.service.SpeechRecognitionService"
        android:exported="true"
        android:permission="android.permission.RECORD_AUDIO">
        <intent-filter>
            <action android:name="android.speech.RecognitionService" />
        </intent-filter>
        <meta-data
            android:name="android.speech"
            android:resource="@xml/recognition_service" />
    </service>
</application>
```

**2. Add `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Optionally add `android:settingsActivity="..."` to expose a gear icon in the
system Voice-input picker.)

**3. Set the service as the system default** (Settings → System → Languages
& input → Voice input picker on stock Android, or via adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Verify** by running the demo app's *Recognizer test* screen, which calls
`SpeechRecognizer.createSpeechRecognizer(ctx)` (no component) and logs every
framework callback — useful for confirming the binder round-trip without
needing logcat.

The service implements `onCheckRecognitionSupport` (API 33+) returning the
25 BCP-47 base languages Parakeet-EOU covers, plus the exact requested
regional tag when it maps to a supported base language. Languages are marked
`installedOnDeviceLanguage` once models are present, or
`supportedOnDeviceLanguage` before download. The service does not take audio
focus from the calling app.

**Caveat:** Gboard, Samsung Keyboard, and Google Assistant bundle their own
recognizers and skip the system default. Apps that explicitly call the
framework `SpeechRecognizer` API (or build their own UI on top of it) are
the ones that flow through your service.

## System text-to-speech (`TextToSpeechService`)

The demo app also exposes
`audio.soniqo.speech.service.SpeechTextToSpeechService`, so Android can select
the app under Settings → System → Languages & input → Text-to-speech output.
This path uses `ModelManager.ensureTtsModels()` and a separate `models_tts/`
cache, so framework TTS downloads Kokoro assets only instead of the full
VAD/STT/enhancer pipeline bundle.

To expose the engine from another app, declare the service:

```xml
<service
    android:name="audio.soniqo.speech.service.SpeechTextToSpeechService"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent-filter>
    <meta-data
        android:name="android.speech.tts"
        android:resource="@xml/tts_engine" />
</service>
```

Add `app/src/main/res/xml/tts_engine.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Performance

Measured on a Galaxy S23 Ultra (SM-S918B), CPU only unless noted. RTF is
wall time ÷ emitted-audio duration: lower is faster, and <1.0 is faster than
real time.

| Model | Task | RTF | Latency | Peak memory |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | Streaming STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M full graph (published, two CPU threads) | TTS | 1.81 | sentence-level | ~604 MB |
| Kokoro 82M short-turn (3.0 s graph, default) | TTS | 0.75–0.88 | bounded replies; safe retry | ~527 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1s TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | <1ms per 32ms chunk | <10 MB |

## Pipeline

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Barge-in supported: speaking during TTS playback interrupts and starts a new transcription.

## Architecture

```text
┌──────────────────────────────────────────────┐
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 lines)            │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (git submodule)  │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (orchestration:        │    │
│  │   pipeline · turn · interruptions)   │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Each model class directly implements the corresponding speech-core interface
(`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) — the
JNI bridge instantiates them and hands references to `VoicePipeline`. No
C-vtable adapter boilerplate.

## Hardware Acceleration

| Chipset | Acceleration |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU fallback | XNNPACK |

For automotive Qualcomm SA8295P / SA8255P with QNN (Hexagon DSP), see
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Related

| Repository | Scope |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Cross-platform C++ pipeline engine + ONNX model wrappers + Linux/embedded examples |
| **speech-android** | Android wrapper — Kotlin SDK + JNI bridge over speech-core |

## License

Apache 2.0
