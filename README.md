# Speech Android

📖 Read in: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-device speech SDK for Android, powered by [ONNX Runtime](https://onnxruntime.ai) and [speech-core](https://github.com/soniqo/speech-core).

Low-memory streaming speech recognition and an optional larger TDT model, both covering 25 European languages, plus text-to-speech, voice activity detection, and noise cancellation — all running locally. No cloud APIs, no data leaves the device.

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
| [Parakeet TDT v3](https://soniqo.audio/guides/parakeet/android) | Broad-coverage STT (optional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 25 European |
| [Nemotron-3.5 multilingual](https://soniqo.audio/guides/nemotron) | Prompt-conditioned streaming STT (optional) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | not yet measured | 100+ (including zh) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Offline STT + translation (optional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/guides/kokoro/android) | Text-to-speech (default) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Streaming text-to-speech (optional, fixed Alba voice) | ~126 MB | not yet measured | English |
| [Supertonic-3](https://soniqo.audio/guides/supertonic) | Text-to-speech (LiteRT, flow-matching, G2P-free, 44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/guides/vad/android) | Voice activity detection | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Any |
| [Sortformer 4-speaker](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | Streaming speaker diarization (optional) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | not yet measured | Any |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | Speaker embeddings (optional) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | not yet measured | Any |
| [DeepFilterNet3](https://soniqo.audio/guides/denoise/android) | Noise cancellation | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | not loaded by default | Any |
| [FunctionGemma 270M](https://soniqo.audio/guides/function-calls) | On-device LLM — structured function / tool calls | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | app-runtime dependent | EN-tuned |

Models are downloaded automatically on first launch via `ModelManager.ensureModels()`.

`SpeechConfig()` defaults to `SttModel.PARAKEET_EOU` and `TtsModel.KOKORO_SHORT_TURN`
to keep SDK integrations and the system recognizer on the low-memory Android
path. The demo app opts into `SttModel.PARAKEET` so its echo and dictation
screens exercise the larger 25-European-language TDT model.

Both Parakeet models always detect the language automatically: neither accepts
`language` or `languageHints`, and neither supports Chinese. To select one
language, including Mandarin, use the prompt-conditioned Nemotron backend:

```kotlin
val sttModel = SttModel.NEMOTRON_MULTILINGUAL
val sttBackend = SttBackend.LITERT
val modelDir = ModelManager.ensureModels(
    context,
    sttModel = sttModel,
    sttBackend = sttBackend,
)
val config = SpeechConfig(
    modelDir = modelDir,
    sttModel = sttModel,
    sttBackend = sttBackend,
    language = "zh-CN", // or "zh-TW"
)
```

**Supertonic-3** is an opt-in higher-quality multilingual TTS — select it with
`SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requires the LiteRT backend). The host runs its four
non-autoregressive flow-matching graphs on-device at 44.1 kHz; the front-end is G2P-free (NFKD +
Unicode index — no phonemizer), so all 31 languages go through one path.
Long sentences: the bundle also ships an optional L=128 latent-window graph pair
(`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 MB). Download it with
`ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)`
(or the same flag on `ModelManager.ensureTtsModels`) and speech-core synthesizes a sentence longer
than the 4.5 s base window in one pass instead of splitting it; the pair is loaded on first use
(+~360 MB RSS). Off by default.

Both Kokoro and Supertonic take a per-call voice preset on direct synthesis —
`pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`; Kokoro `af_heart`,
`ff_siwis`, …) — and the same argument exists on `synthesizeStreaming` and `SpeechSynthesizer`.
The preset applies to that call only; omit it (or pass `""`) to keep the engine default, and an
unknown id throws.

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
    implementation("audio.soniqo:speech:0.0.24")
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

### End-of-turn detection

A VAD hears silence, but cannot tell whether a pause finishes the speaker's
thought. Smart Turn v3.2 is an optional audio-native classifier that considers
the last eight seconds of the current turn and keeps an incomplete turn open.

```kotlin
val modelDir = ModelManager.ensureModels(context, enableSmartTurn = true)

val pipeline = SpeechPipeline(
    SpeechConfig(
        modelDir = modelDir,
        enableSmartTurn = true,
        turnCompletionThreshold = 0.5f,
        turnCompletionMaxSilenceSec = 2.0f,
    )
)
```

The opt-in download adds the pinned 11.1 MB int8 graph. Inference runs once
after each confirmed VAD pause, not on every audio frame. A vetoed pause stays
in the same turn if speech resumes; the maximum-silence setting guarantees that
a trailing speaker still reaches an endpoint. Smart Turn is created by
Pipecat/Daily and distributed under BSD-2-Clause.

### Voice activity detection only

Apps that only need to know *when* someone is speaking — push-to-talk
gating, recording segmentation, wake-on-voice — can load Silero on its own:
a 2 MB download and under 10 MB of RAM instead of the ~500 MB default set.
No STT, no TTS, no pipeline.

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // opt in to receive the captured segment
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz mono float32, 512-sample frames
detector.pushAudio(samples)
// close an open segment when the stream ends
detector.flush()
```

`VadConfig` exposes the same turn detection the pipeline uses internally:
onset and offset thresholds, minimum speech duration, end-of-speech silence,
and the pre-speech buffer that keeps the first syllable. This path uses
`ModelManager.ensureVadModels()` and a separate `models_vad/` cache, so it
never pulls the STT/TTS bundles.

### Meeting transcription building blocks

Apps that run their own capture and segmentation — a meeting recorder, a
note taker — can load the recognizer, the diarizer and the speaker encoder
on their own, without the pipeline. Each has its own download and cache
directory, and none makes a product decision: the transcriber returns text,
the diarizer per-frame speaker probabilities, and the embedder a
192-dimensional voice vector. Thresholds, turns, speaker labels and voice
matching stay in the app.

```kotlin
// Optional: a mirror the user chose, serving Hugging Face's layout.
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz mono float32; text so far
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [frames x diarizer.speakers], often empty
val tail = diarizer.endStream()           // when the recording ends

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // at least 2 s; FloatArray(192)
```

`TranscriberConfig.language` defaults to `"auto"`, Nemotron's
automatic-language prompt, as in speech-swift; `"en-US"`, `"pt_BR"` or a
bare `"fr"` pins one language. Each transcript carries word timings, in seconds from `beginStream`,
taken from the encoder frame each token was emitted on.
Sortformer is one stream per recording and answers about 30 seconds behind
the audio, because the published export decodes 27.2 seconds per call.
`ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)`
fetches the three sets in the background.

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
- 25-European-language Parakeet TDT STT in the echo and dictation screens
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
`ACTION_SET_TEXT`. Fields whose real contents cannot be read — some apps report
their placeholder as the field's own text — are written by pasting instead,
which replaces whatever was on the clipboard; the dictation is cleared from it
right after.

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
