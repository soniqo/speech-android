# Speech Android

📖 Langues : [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK vocal sur appareil pour Android, propulsé par [ONNX Runtime](https://onnxruntime.ai) et [speech-core](https://github.com/soniqo/speech-core).

Reconnaissance vocale (114 langues), synthèse vocale (8 langues), détection d'activité vocale et suppression de bruit — tout fonctionne en local. Aucune API cloud, aucune donnée ne quitte l'appareil.

**[APK de démo](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modèles](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (équivalent Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (moteur de pipeline + build Linux/embarqué)

## Périmètre

Ce dépôt fournit le **packaging Android** : SDK Kotlin, pont JNI, application de démo. Le moteur C++ et les wrappers de modèles ONNX (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) résident dans [speech-core](https://github.com/soniqo/speech-core) et sont intégrés via un sous-module git. Le volet Linux / automobile (Yocto, Qualcomm SA8295P/SA8255P) se trouve dans [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modèles

| Modèle | Tâche | Taille INT8 | Langues |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | Reconnaissance vocale | 891 Mo | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Synthèse vocale | 330 Mo | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Détection d'activité vocale | 2 Mo | Toutes |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Suppression de bruit | ~8 Mo | Toutes |

Les modèles sont téléchargés automatiquement au premier lancement via `ModelManager.ensureModels()`.

## Essayer la démo

Téléchargez l'[APK signé](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) et installez-le sur n'importe quel appareil Android arm64 (8+). Les modèles (~1,2 Go) sont téléchargés automatiquement au premier lancement.

## Ajouter la dépendance

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Utilisation Kotlin

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

// Alimente avec du PCM float32 mono 16 kHz depuis le micro
pipeline.pushAudio(samples)
```

## Compiler depuis les sources

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 tests e2e
```

`./setup.sh` initialise le sous-module speech-core et télécharge ONNX Runtime
dans `./ort/`.

## Application de démo

Le module [`app/`](app/) est une démo minimale d'assistant vocal avec :

- Visualisation de la forme d'onde VAD en temps réel
- Mode écho : transcrit la voix et la synthétise en retour (sans LLM)
- Mode dictée : résultats partiels en streaming
- Écran de test `SpeechRecognizer` — exerce le chemin d'entrée vocale à l'échelle du système
- Interface de bulles de chat avec affichage de la latence STT/TTS

```bash
./gradlew :app:installDebug
```

## Entrée vocale système (`RecognitionService`)

Le SDK fournit un `audio.soniqo.speech.service.SpeechRecognitionService` prêt à l'emploi qui s'intègre à l'API `SpeechRecognizer` du framework Android — aucun code à écrire. Une fois votre app sélectionnée comme reconnaisseur vocal par défaut, toute application tierce appelant `SpeechRecognizer.createSpeechRecognizer(context)` (sans `ComponentName`) obtient un STT entièrement on-device via votre pipeline.

**1. Déclarez `RECORD_AUDIO` et le service dans `AndroidManifest.xml` :**

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

**2. Ajoutez `app/src/main/res/xml/recognition_service.xml` :**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Ajoutez optionnellement `android:settingsActivity="..."` pour afficher une icône d'engrenage dans le sélecteur d'entrée vocale système.)

**3. Définissez le service comme valeur par défaut système** (Paramètres → Système → Langues et saisie → Sélecteur d'entrée vocale sur Android pur, ou via adb) :

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Vérifiez** en lançant l'écran *Recognizer test* de l'app de démo, qui appelle `SpeechRecognizer.createSpeechRecognizer(ctx)` (sans composant) et journalise chaque callback du framework — utile pour confirmer l'aller-retour binder sans avoir besoin de logcat.

Le service implémente `onCheckRecognitionSupport` (API 33+) renvoyant les 27 langues BCP-47 couvertes par Parakeet TDT v3, marquées `installedOnDeviceLanguage` lorsque les modèles sont présents (ou `pendingOnDeviceLanguage` pendant leur téléchargement). Le focus audio est acquis avec `AUDIOFOCUS_GAIN_TRANSIENT` pendant la durée d'une session.

**Limitation :** Gboard, Samsung Keyboard et Google Assistant intègrent leurs propres reconnaisseurs et contournent la valeur par défaut système. Les applications qui appellent explicitement l'API `SpeechRecognizer` du framework (ou construisent leur propre UI par-dessus) sont celles qui passent par votre service.

## Performance

Mesuré sur émulateur Android (arm64-v8a, sans NNAPI). Le matériel réel est nettement plus rapide.

| Modèle | Tâche | Audio | Inférence | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5 s | 175 ms | 0,12 |
| Kokoro 82M | TTS | 1,9 s en sortie | 1 075 ms | 0,58 |
| Silero VAD v5 | VAD | bloc 32 ms | <1 ms | <0,01 |

## Pipeline

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Le barge-in est pris en charge : parler pendant la lecture TTS l'interrompt et démarre une nouvelle transcription.

## Architecture

```text
┌──────────────────────────────────────────────┐
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 lignes)           │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (sous-module)    │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (orchestration :       │    │
│  │   pipeline · tour · interruptions)   │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Chaque classe de modèle implémente directement l'interface speech-core correspondante (`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) — le pont JNI les instancie et transmet les références à `VoicePipeline`. Aucun boilerplate d'adaptateur de vtable C.

## Accélération matérielle

| Chipset | Accélération |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| Repli CPU | XNNPACK |

Pour les plateformes automobiles Qualcomm SA8295P / SA8255P avec QNN (Hexagon DSP), voir [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Projets liés

| Dépôt | Périmètre |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Moteur de pipeline C++ multiplateforme + wrappers de modèles ONNX + exemples Linux/embarqué |
| **speech-android** | Wrapper Android — SDK Kotlin + pont JNI sur speech-core |

## Licence

Apache 2.0
