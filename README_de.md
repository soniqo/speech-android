# Speech Android

📖 Sprachen: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-Device Speech-SDK für Android und Embedded Linux, basierend auf [ONNX Runtime](https://onnxruntime.ai) und [speech-core](https://github.com/soniqo/speech-core).

Spracherkennung (114 Sprachen), Text-to-Speech (8 Sprachen), Sprachaktivitätserkennung und Rauschunterdrückung — alles lokal ausgeführt. Keine Cloud-APIs, keine Daten verlassen das Gerät.

**[Demo-APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelle](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple-Pendant) · **[speech-core](https://github.com/soniqo/speech-core)** (Pipeline-Engine)

## Plattformen

| Plattform | API | Beschleunigung | Verzeichnis |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Embedded Linux | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Modelle

| Modell | Aufgabe | INT8-Größe | Sprachen |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Spracherkennung | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Text-to-Speech | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Sprachaktivitätserkennung | 2 MB | Beliebig |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Rauschunterdrückung | ~8 MB | Beliebig |

Modelle werden beim ersten Start automatisch heruntergeladen (Android) oder manuell platziert (Linux).

## Android

### Demo ausprobieren

Lade das [signierte APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) herunter und installiere es auf einem beliebigen arm64-Android-Gerät (8+). Modelle (~1,2 GB) werden beim ersten Start automatisch heruntergeladen.

### Abhängigkeit hinzufügen

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Kotlin-Verwendung

```kotlin
val modelDir = ModelManager.ensureModels(context)

val pipeline = SpeechPipeline(
    SpeechConfig(modelDir = modelDir, useNnapi = true)
)

pipeline.events.collect { event ->
    when (event) {
        is SpeechEvent.TranscriptionCompleted -> println(event.text)
        is SpeechEvent.ResponseDone -> pipeline.resumeListening()
        else -> {}
    }
}

pipeline.start()

// Speise 16kHz Mono float32 PCM vom Mikrofon ein
pipeline.pushAudio(samples)
```

### Aus dem Quellcode bauen

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e-Tests
```

### Demo-App

Das Modul [`app/`](app/) ist eine minimale Sprachassistenten-Demo mit:

- Echtzeit-VAD-Wellenformvisualisierung
- Echo-Modus: transkribiert Sprache und synthetisiert sie zurück (kein LLM)
- Chat-Bubble-UI mit STT/TTS-Latenzanzeige

```bash
./gradlew :app:installDebug
```

## Leistung

Gemessen auf einem Android-Emulator (arm64-v8a, ohne NNAPI). Echte Hardware ist deutlich schneller.

| Modell | Aufgabe | Audio | Inferenz | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5s | 175ms | 0,12 |
| Kokoro 82M | TTS | 1,9s Ausgabe | 1.075ms | 0,58 |
| Silero VAD v5 | VAD | 32ms-Block | <1ms | <0,01 |

## Embedded Linux

Minimale C-API für Automotive- und Embedded-Plattformen. Vollständige Dokumentation siehe [`linux/README.md`](linux/README.md).

### C-API-Verwendung

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon-DSP-Beschleunigung

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### Bauen

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### Testen

```bash
linux/tests/download_models.sh              # ONNX-Modelle herunterladen
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 Tests
```

### Cross-Compile für Yocto

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

## Pipeline

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Barge-In wird unterstützt: Sprechen während der TTS-Wiedergabe unterbricht und startet eine neue Transkription.

## Architektur

```text
┌──────────────────────────────────────────────┐
│   Android: SpeechPipeline (Kotlin/JNI)       │
│   Linux:   speech.h (C API)                  │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────┴───────────────────────────┐
│            speech-core (C++ submodule)        │
│   Turn detection · Interruptions · Context   │
└──┬────────┬────────┬────────┬────────────────┘
   │        │        │        │  vtables
┌──┴──┐  ┌──┴──┐  ┌──┴──┐  ┌─┴────────┐
│ VAD │  │ STT │  │ TTS │  │ Enhancer │
│Silero│  │Para-│  │Koko-│  │DeepFilter│
│     │  │keet │  │ro   │  │Net3      │
└──┬──┘  └──┬──┘  └──┬──┘  └─┬────────┘
   └────────┴────────┴────────┘
       ONNX Runtime (CPU / NNAPI / QNN)
```

## Hardwarebeschleunigung

| Plattform | Chipsatz | Beschleunigung |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Automotive | SA8295P / SA8255P | QNN → Hexagon DSP |
| Beliebig | CPU-Fallback | XNNPACK |

## Verwandte Projekte

| Repository | Plattform |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Plattformübergreifende C++-Pipeline-Engine |
| **speech-android** | Android + Embedded Linux — ONNX Runtime |

## Lizenz

Apache 2.0
