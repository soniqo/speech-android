# Speech Android

📖 Langues : [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK vocal sur appareil pour Android, propulsé par [ONNX Runtime](https://onnxruntime.ai) et [speech-core](https://github.com/soniqo/speech-core).

Reconnaissance vocale en streaming à faible mémoire (25 langues par défaut, TDT 114 langues en option), synthèse vocale, détection d'activité vocale et suppression de bruit — tout fonctionne en local. Aucune API cloud, aucune donnée ne quitte l'appareil.

**[APK de démo](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modèles](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (équivalent Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (moteur de pipeline + build Linux/embarqué)

## Périmètre

Ce dépôt fournit le **packaging Android** : SDK Kotlin, pont JNI, application de démo. Le moteur C++ et les wrappers de modèles ONNX (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) résident dans [speech-core](https://github.com/soniqo/speech-core) et sont intégrés via un sous-module git. Le volet Linux / automobile (Yocto, Qualcomm SA8295P/SA8255P) se trouve dans [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modèles

| Modèle | Tâche | Téléchargement | Mémoire max | Langues |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | STT streaming + EOU (défaut) | 153 Mo | 232 Mo | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | STT large couverture (optionnel) | 891 Mo | ~1,1-1,3 Go | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Synthèse vocale (défaut) | 330 Mo | 640 Mo | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | Synthèse vocale (LiteRT, flow-matching, G2P-free, 44,1 kHz) | ~380 Mo | 832 Mo | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Détection d'activité vocale | 2 Mo | <10 Mo | Toutes |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Suppression de bruit | ~8 Mo | non chargé par défaut | Toutes |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | LLM sur appareil — appels structurés de fonctions / outils | 283 Mo | dépend du runtime de l'app | Ajusté EN |

Les modèles sont téléchargés automatiquement au premier lancement via `ModelManager.ensureModels()`.

`SpeechConfig()` utilise `SttModel.PARAKEET_EOU` et `TtsModel.KOKORO` par défaut afin que la démo et le service de reconnaissance système restent sur le chemin Android à faible mémoire. Utilisez `SpeechConfig(sttModel = SttModel.PARAKEET)` uniquement si vous avez besoin du modèle TDT plus grand à 114 langues.

**Supertonic-3** est une synthèse vocale multilingue de meilleure qualité, activable en option — sélectionnez-la avec `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (nécessite le backend LiteRT). L'hôte exécute ses quatre graphes de flow-matching non autorégressifs en local à 44,1 kHz ; le front-end est G2P-free (NFKD + index Unicode — aucun phonémiseur), de sorte que les 31 langues passent par un seul chemin.

## Essayer la démo

Téléchargez l'[APK signé](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) et installez-le sur n'importe quel appareil Android arm64 (8+). Le bundle de modèles faible mémoire par défaut (~500 Mo) est téléchargé automatiquement au premier lancement.

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

Le service implémente `onCheckRecognitionSupport` (API 33+) et renvoie les 25 langues de base BCP-47 couvertes par Parakeet-EOU, plus le tag régional exact demandé lorsqu'il correspond à une langue de base prise en charge. Les langues sont marquées `installedOnDeviceLanguage` lorsque les modèles sont présents, ou `supportedOnDeviceLanguage` avant téléchargement. Le service ne prend pas le focus audio à l'app appelante.

**Limitation :** Gboard, Samsung Keyboard et Google Assistant intègrent leurs propres reconnaisseurs et contournent la valeur par défaut système. Les applications qui appellent explicitement l'API `SpeechRecognizer` du framework (ou construisent leur propre UI par-dessus) sont celles qui passent par votre service.

## Synthèse vocale système (`TextToSpeechService`)

L'app de démo expose aussi `audio.soniqo.speech.service.SpeechTextToSpeechService`, ce qui permet à Android de sélectionner l'app dans Paramètres → Système → Langues et saisie → Sortie de synthèse vocale. Ce chemin utilise `ModelManager.ensureTtsModels()` et un cache séparé `models_tts/`, donc le TTS du framework ne télécharge que les ressources Kokoro au lieu du bundle complet VAD/STT/enhancer.

Pour exposer le moteur depuis une autre app, déclarez le service :

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

Ajoutez `app/src/main/res/xml/tts_engine.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Performance

Mesuré sur Galaxy S23 Android, CPU seul sauf indication. Un RTF plus bas est plus rapide.

| Modèle | Tâche | RTF | Latence | Mémoire max |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | STT streaming + EOU | 0,21 | partiels streaming | 232 Mo |
| Kokoro 82M ONNX FP32 | TTS | 0,53 | par phrase | 640 Mo |
| Supertonic-3 LiteRT | TTS | 0,34 | ~1,1 s TTFA | 832 Mo |
| Silero VAD v5 | VAD | <0,01 | <1 ms par bloc 32 ms | <10 Mo |

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
