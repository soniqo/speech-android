# Speech Android

📖 Sprachen: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-Device Speech-SDK für Android, basierend auf [ONNX Runtime](https://onnxruntime.ai) und [speech-core](https://github.com/soniqo/speech-core).

Speicherarme Streaming-Spracherkennung (standardmäßig 25 Sprachen, optionales TDT mit 114 Sprachen), Text-to-Speech, Sprachaktivitätserkennung und Rauschunterdrückung — alles lokal ausgeführt. Keine Cloud-APIs, keine Daten verlassen das Gerät.

**[Demo-APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelle](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple-Pendant) · **[speech-core](https://github.com/soniqo/speech-core)** (Pipeline-Engine + Linux/Embedded-Build)

## Geltungsbereich

Dieses Repo ist das **Android-Packaging**: Kotlin-SDK, JNI-Bridge, Demo-App. Die C++-Engine und die ONNX-Modell-Wrapper (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) liegen in [speech-core](https://github.com/soniqo/speech-core) und werden über ein Git-Submodul eingebunden. Linux / Automotive (Yocto, Qualcomm SA8295P/SA8255P) befindet sich unter [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelle

| Modell | Aufgabe | Download | Spitzen-Speicher | Sprachen |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | Streaming-STT + EOU (Standard) | 153 MB | 232 MB | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | Breite STT-Abdeckung (optional) | 891 MB | ~1,1-1,3 GB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Text-to-Speech (Standard) | 330 MB | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | Text-to-Speech (LiteRT, Flow-Matching, G2P-frei, 44,1 kHz) | ~380 MB | 832 MB | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Sprachaktivitätserkennung | 2 MB | <10 MB | Beliebig |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Rauschunterdrückung | ~8 MB | standardmäßig nicht geladen | Beliebig |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | On-Device-LLM — strukturierte Funktions-/Tool-Aufrufe | 283 MB | abhängig vom App-Runtime | EN-getunt |

Modelle werden beim ersten Start automatisch über `ModelManager.ensureModels()` heruntergeladen.

`SpeechConfig()` verwendet standardmäßig `SttModel.PARAKEET_EOU` und `TtsModel.KOKORO`, damit Demo und Systemerkennung den speicherarmen Android-Pfad nutzen. Verwende `SpeechConfig(sttModel = SttModel.PARAKEET)` nur, wenn das größere TDT-Modell mit 114 Sprachen benötigt wird.

**Supertonic-3** ist ein optionales, höherwertiges mehrsprachiges TTS — wähle es mit `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` aus (erfordert das LiteRT-Backend). Der Host führt seine vier nicht-autoregressiven Flow-Matching-Graphen mit 44,1 kHz auf dem Gerät aus; das Front-End ist G2P-frei (NFKD + Unicode-Index — kein Phonemizer), sodass alle 31 Sprachen über einen einzigen Pfad laufen.

## Demo ausprobieren

Lade das [signierte APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) herunter und installiere es auf einem beliebigen arm64-Android-Gerät (8+). Das standardmäßige speicherarme Modellpaket (~500 MB) wird beim ersten Start automatisch heruntergeladen.

## Abhängigkeit hinzufügen

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin-Verwendung

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

## Aus dem Quellcode bauen

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e-Tests
```

`./setup.sh` initialisiert das speech-core-Submodul und lädt die ONNX Runtime
nach `./ort/` herunter.

## Demo-App

Das Modul [`app/`](app/) ist eine minimale Sprachassistenten-Demo mit:

- Echtzeit-VAD-Wellenformvisualisierung
- Echo-Modus: transkribiert Sprache und synthetisiert sie zurück (kein LLM)
- Diktiermodus: Streaming-Teilergebnisse
- `SpeechRecognizer`-Testbildschirm — übt den systemweiten Spracheingabepfad aus
- Chat-Bubble-UI mit STT/TTS-Latenzanzeige

```bash
./gradlew :app:installDebug
```

## Systemweite Spracheingabe (`RecognitionService`)

Das SDK enthält einen einsatzbereiten `audio.soniqo.speech.service.SpeechRecognitionService`, der sich in die `SpeechRecognizer`-API des Android-Frameworks einklinkt — kein Code zu schreiben. Sobald deine App als Standard-Spracherkennung ausgewählt ist, erhält jede Drittanbieter-App, die `SpeechRecognizer.createSpeechRecognizer(context)` (ohne `ComponentName`) aufruft, vollständiges On-Device-STT über deine Pipeline.

**1. Deklariere `RECORD_AUDIO` und den Dienst in `AndroidManifest.xml`:**

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

**2. Füge `app/src/main/res/xml/recognition_service.xml` hinzu:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Optional kannst du `android:settingsActivity="..."` hinzufügen, um ein Zahnrad-Icon im systemweiten Spracheingabe-Picker anzuzeigen.)

**3. Setze den Dienst als System-Standard** (Einstellungen → System → Sprachen & Eingabe → Spracheingabe-Auswahl auf Stock-Android, oder über adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Verifiziere**, indem du den *Recognizer test*-Bildschirm der Demo-App ausführst, der `SpeechRecognizer.createSpeechRecognizer(ctx)` (ohne Komponente) aufruft und jeden Framework-Callback protokolliert — nützlich, um den Binder-Roundtrip ohne logcat zu bestätigen.

Der Dienst implementiert `onCheckRecognitionSupport` (API 33+) und gibt die 25 BCP-47-Sprachen zurück, die Parakeet-EOU abdeckt, markiert als `installedOnDeviceLanguage`, sobald Modelle vorhanden sind (oder `pendingOnDeviceLanguage`, während sie heruntergeladen werden). Während einer Sitzung wird Audiofokus mit `AUDIOFOCUS_GAIN_TRANSIENT` angefordert.

**Einschränkung:** Gboard, Samsung Keyboard und Google Assistant bringen eigene Erkenner mit und überspringen den System-Standard. Apps, die die Framework-`SpeechRecognizer`-API explizit aufrufen (oder eine eigene UI darauf aufbauen), gehen über deinen Dienst.

## Systemweite Sprachausgabe (`TextToSpeechService`)

Die Demo-App stellt außerdem `audio.soniqo.speech.service.SpeechTextToSpeechService` bereit, sodass Android die App unter Einstellungen → System → Sprachen & Eingabe → Text-in-Sprache-Ausgabe auswählen kann. Dieser Pfad verwendet `ModelManager.ensureTtsModels()` und einen separaten `models_tts/`-Cache, sodass Framework-TTS nur Kokoro-Assets lädt statt des vollständigen VAD/STT/Enhancer-Pipeline-Bundles.

Um die Engine aus einer anderen App bereitzustellen, deklariere den Dienst:

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

Füge `app/src/main/res/xml/tts_engine.xml` hinzu:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Leistung

Gemessen auf Galaxy S23 Android, sofern nicht anders angegeben nur CPU. Niedrigeres RTF ist schneller.

| Modell | Aufgabe | RTF | Latenz | Spitzen-Speicher |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | Streaming-STT + EOU | 0,21 | Streaming-Teilergebnisse | 232 MB |
| Kokoro 82M ONNX FP32 | TTS | 0,53 | satzweise | 640 MB |
| Supertonic-3 LiteRT | TTS | 0,34 | ~1,1s TTFA | 832 MB |
| Silero VAD v5 | VAD | <0,01 | <1ms pro 32ms-Block | <10 MB |

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
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 Zeilen)           │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (Git-Submodul)   │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (Orchestrierung:       │    │
│  │   Pipeline · Turn · Interruptions)   │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Jede Modellklasse implementiert direkt die entsprechende speech-core-Schnittstelle
(`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) — die
JNI-Bridge instanziiert sie und übergibt Referenzen an `VoicePipeline`. Kein
C-vtable-Adapter-Boilerplate.

## Hardwarebeschleunigung

| Chipsatz | Beschleunigung |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU-Fallback | XNNPACK |

Für Automotive Qualcomm SA8295P / SA8255P mit QNN (Hexagon DSP) siehe
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Verwandte Projekte

| Repository | Geltungsbereich |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Plattformübergreifende C++-Pipeline-Engine + ONNX-Modell-Wrapper + Linux/Embedded-Beispiele |
| **speech-android** | Android-Wrapper — Kotlin-SDK + JNI-Bridge über speech-core |

## Lizenz

Apache 2.0
