# Speech Android

📖 Sprachen: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

On-Device Speech-SDK für Android, basierend auf [ONNX Runtime](https://onnxruntime.ai) und [speech-core](https://github.com/soniqo/speech-core).

Speicherarme Streaming-Spracherkennung (standardmäßig 25 Sprachen, optionales TDT mit 114 Sprachen), Text-to-Speech, Sprachaktivitätserkennung und Rauschunterdrückung — alles lokal ausgeführt. Keine Cloud-APIs, keine Daten verlassen das Gerät.

**[📚 Android-Dokumentation](https://soniqo.audio/de/getting-started/android)**

**[Demo-APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control-Demo-APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Modelle](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple-Pendant) · **[speech-core](https://github.com/soniqo/speech-core)** (Pipeline-Engine + Linux/Embedded-Build)

## Demo

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="Ein komplett offline laufender Sprachagent in 1,2 GB auf Android — Demo auf YouTube ansehen">
  </a>
</p>
<p align="center"><em>Die komplette Befehlsschleife der <a href="control-demo/">control-demo</a> — Silero VAD → Parakeet STT → FunctionGemma → Geräteaktion → Pocket-TTS-Antwort — vollständig offline in 1,2 GB RAM</em></p>

## Geltungsbereich

Dieses Repo ist das **Android-Packaging**: Kotlin-SDK, JNI-Bridge, Demo-App. Die C++-Engine und die ONNX-Modell-Wrapper (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) liegen in [speech-core](https://github.com/soniqo/speech-core) und werden über ein Git-Submodul eingebunden. Linux / Automotive (Yocto, Qualcomm SA8295P/SA8255P) befindet sich unter [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelle

| Modell | Aufgabe | Download | Spitzen-Speicher | Sprachen |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/de/guides/dictate) | Streaming-STT + EOU (Standard) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/de/guides/parakeet/android) | Breite STT-Abdeckung (optional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 GB | 114 |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Offline-STT + Übersetzung (optional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/de/guides/kokoro/android) | Text-to-Speech (Standard) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Streaming-Text-to-Speech (optional, feste Alba-Stimme) | ~126 MB | noch nicht gemessen | Englisch |
| [Supertonic-3](https://soniqo.audio/de/guides/supertonic) | Text-to-Speech (LiteRT, Flow-Matching, G2P-frei, 44,1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/de/guides/vad/android) | Sprachaktivitätserkennung | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Beliebig |
| [DeepFilterNet3](https://soniqo.audio/de/guides/denoise/android) | Rauschunterdrückung | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | standardmäßig nicht geladen | Beliebig |
| [FunctionGemma 270M](https://soniqo.audio/de/guides/function-calls) | On-Device-LLM — strukturierte Funktions-/Tool-Aufrufe | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | abhängig vom App-Runtime | EN-getunt |

Modelle werden beim ersten Start automatisch über `ModelManager.ensureModels()` heruntergeladen.

`SpeechConfig()` verwendet standardmäßig `SttModel.PARAKEET_EOU` und `TtsModel.KOKORO_SHORT_TURN`, damit SDK-Integrationen und Systemerkennung den speicherarmen Android-Pfad nutzen. Die Demo-App wählt `SttModel.PARAKEET`, sodass Echo- und Diktieransicht das größere TDT-Modell mit 114 Sprachen verwenden.

Für sprachfokussierte Erkennung verwende `SpeechConfig(sttModel = SttModel.PARAKEET, languageHints = listOf("en", "fr"))`. Setze `language = "en"`, wenn genau eine Sprache fest vorgegeben werden soll.

**Supertonic-3** ist ein optionales, höherwertiges mehrsprachiges TTS — wähle es mit `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` aus (erfordert das LiteRT-Backend). Der Host führt seine vier nicht-autoregressiven Flow-Matching-Graphen mit 44,1 kHz auf dem Gerät aus; das Front-End ist G2P-frei (NFKD + Unicode-Index — kein Phonemizer), sodass alle 31 Sprachen über einen einzigen Pfad laufen.

## Demo ausprobieren

Lade das [signierte APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) herunter und installiere es auf einem beliebigen arm64-Android-Gerät (8+). Das standardmäßige speicherarme Modellpaket (~500 MB) wird beim ersten Start automatisch heruntergeladen.

## Abhängigkeit hinzufügen

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.15")
}
```

## Kotlin-Verwendung

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

// Speise 16kHz Mono float32 PCM vom Mikrofon ein
pipeline.pushAudio(samples)
```

## Aus dem Quellcode bauen

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 e2e-Tests
```

`./setup.sh` initialisiert das speech-core-Submodul und lädt die ONNX Runtime
nach `./ort/` herunter.

## Demo-App

Das Modul [`app/`](app/) ist eine minimale Sprachassistenten-Demo mit:

- Echtzeit-VAD-Wellenformvisualisierung
- Echo-Modus: transkribiert Sprache und synthetisiert sie zurück (kein LLM)
- Diktiermodus: Streaming-Teilergebnisse
- Sprach-Overlay: schwebende Mikrofon-Schaltfläche zum Diktieren in jede App
- Parakeet TDT STT mit 114 Sprachen in Echo- und Diktieransicht
- `SpeechRecognizer`-Testbildschirm — übt den systemweiten Spracheingabepfad aus
- Chat-Bubble-UI mit STT/TTS-Latenzanzeige

```bash
./gradlew :app:installDebug
```

### Sprach-Overlay (in jede App diktieren)

Das **Sprach-Overlay** legt eine verschiebbare Mikrofon-Schaltfläche über
andere Apps. Ein Tippen verwandelt sie in **■ Stopp** / **✕ Abbrechen**: Stopp
schreibt den Text in das gerade fokussierte Textfeld, Abbrechen verwirft ihn.
Ist kein bearbeitbares Feld fokussiert, landet der Text in der Zwischenablage,
statt verloren zu gehen.

Drei Berechtigungen sind nötig, jede mit eigenem Systembildschirm — der
Einrichtungsbildschirm zeigt, welche noch fehlen:

| Berechtigung | Wozu |
| --- | --- |
| Mikrofon | Audio aufnehmen |
| Über anderen Apps anzeigen | Schaltfläche außerhalb der App zeichnen |
| Bedienungshilfe | in das Textfeld einer anderen App schreiben |

Das Overlay-Fenster ist bewusst nicht fokussierbar, damit das Zielfeld beim
Tippen auf die Schaltflächen den Eingabefokus behält. Der Text wird per
`ACTION_SET_TEXT` an der Cursorposition eingefügt. Felder, deren tatsächlicher
Inhalt nicht lesbar ist — manche Apps melden ihren Platzhalter als eigenen
Feldtext —, werden stattdessen per Einfügen beschrieben, was den bisherigen
Inhalt der Zwischenablage ersetzt; das Diktat wird unmittelbar danach daraus
gelöscht.

> Installation per APK statt über den Play Store? Android sperrt den
> Bedienungshilfe-Schalter, bis er unter
> Einstellungen → Apps → Speech → ⋮ → **Eingeschränkte Einstellungen zulassen**
> freigegeben wird.

### Vollständige Control-Pipeline-Demo

Die separate App [`control-demo/`](control-demo/) führt den kompletten Agenten
lokal aus: Silero VAD → Parakeet-EOU STT → FunctionGemma-270M-Tool-Aufrufe →
Android-Geräteaktionen → Pocket TTS. Sie zeigt die Latenz jeder Stufe an und
bindet direkt das `:sdk` dieses Checkouts ein, sodass lokale
Sprachoptimierungen verwendet werden.

Lade das [signierte Control-Demo-APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
aus dem neuesten Release herunter oder installiere einen Entwicklungs-Build aus dem Quellcode:

```bash
./gradlew :control-demo:installDebug
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

Der Dienst implementiert `onCheckRecognitionSupport` (API 33+) und gibt die 25 BCP-47-Basissprachen zurück, die Parakeet-EOU abdeckt, plus das exakt angeforderte regionale Tag, wenn es zu einer unterstützten Basissprache gehört. Sprachen werden als `installedOnDeviceLanguage` markiert, sobald Modelle vorhanden sind, oder vor dem Download als `supportedOnDeviceLanguage`. Der Dienst nimmt der aufrufenden App keinen Audiofokus weg.

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

Gemessen auf einem Galaxy S23 Ultra (SM-S918B), sofern nicht anders angegeben nur CPU. RTF ist
Wandzeit ÷ Dauer des ausgegebenen Audios: niedriger ist schneller, und <1,0 ist schneller als Echtzeit.

| Modell | Aufgabe | RTF | Latenz | Spitzen-Speicher |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | Streaming-STT + EOU | 0,21 | Streaming-Teilergebnisse | 232 MB |
| Kokoro 82M vollständiger Graph (veröffentlicht, CPU mit zwei Threads) | TTS | 1,81 | satzweise | ~604 MB |
| Kokoro 82M kurze Antwort (3,0-s-Graph, Standard) | TTS | 0,75–0,88 | begrenzte Antworten; sicherer Retry | ~527 MB |
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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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
