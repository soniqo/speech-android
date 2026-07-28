# Speech Android

📖 Langues : [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK vocal sur appareil pour Android, propulsé par [ONNX Runtime](https://onnxruntime.ai) et [speech-core](https://github.com/soniqo/speech-core).

Reconnaissance vocale en streaming à faible mémoire (25 langues par défaut, TDT 114 langues en option), synthèse vocale, détection d'activité vocale et suppression de bruit — tout fonctionne en local. Aucune API cloud, aucune donnée ne quitte l'appareil.

**[📚 Documentation Android](https://soniqo.audio/fr/getting-started/android)**

**[APK de démo](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[APK Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Modèles](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (équivalent Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (moteur de pipeline + build Linux/embarqué)

## Démonstration

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="Un agent vocal entièrement hors ligne dans 1,2 Go sur Android — voir la démo sur YouTube">
  </a>
</p>
<p align="center"><em>La boucle de commande complète de <a href="control-demo/">control-demo</a> — Silero VAD → Parakeet STT → FunctionGemma → action de l'appareil → réponse Pocket TTS — entièrement hors ligne dans 1,2 Go de RAM</em></p>

## Périmètre

Ce dépôt fournit le **packaging Android** : SDK Kotlin, pont JNI, application de démo. Le moteur C++ et les wrappers de modèles ONNX (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) résident dans [speech-core](https://github.com/soniqo/speech-core) et sont intégrés via un sous-module git. Le volet Linux / automobile (Yocto, Qualcomm SA8295P/SA8255P) se trouve dans [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modèles

| Modèle | Tâche | Téléchargement | Mémoire max | Langues |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/fr/guides/dictate) | STT streaming + EOU (défaut) | [153 Mo](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 Mo | 25 |
| [Parakeet TDT v3](https://soniqo.audio/fr/guides/parakeet/android) | STT large couverture (optionnel) | [891 Mo](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 Go | 114 |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | STT hors ligne + traduction (optionnel) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/fr/guides/kokoro/android) | Synthèse vocale (défaut) | [330 Mo](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 Mo | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Synthèse vocale streaming (optionnel, voix Alba fixe) | ~126 Mo | pas encore mesuré | Anglais |
| [Supertonic-3](https://soniqo.audio/fr/guides/supertonic) | Synthèse vocale (LiteRT, flow-matching, G2P-free, 44,1 kHz) | [~380 Mo](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 Mo | 31 |
| [Silero VAD v5](https://soniqo.audio/fr/guides/vad/android) | Détection d'activité vocale | [2 Mo](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 Mo | Toutes |
| [DeepFilterNet3](https://soniqo.audio/fr/guides/denoise/android) | Suppression de bruit | [~8 Mo](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | non chargé par défaut | Toutes |
| [FunctionGemma 270M](https://soniqo.audio/fr/guides/function-calls) | LLM sur appareil — appels structurés de fonctions / outils | [283 Mo](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | dépend du runtime de l'app | Ajusté EN |

Les modèles sont téléchargés automatiquement au premier lancement via `ModelManager.ensureModels()`.

`SpeechConfig()` utilise `SttModel.PARAKEET_EOU` et `TtsModel.KOKORO_SHORT_TURN` par défaut afin que les intégrations SDK et le service de reconnaissance système restent sur le chemin Android à faible mémoire. L'application de démo sélectionne `SttModel.PARAKEET` pour que les écrans écho et dictée utilisent le modèle TDT plus grand à 114 langues.

Pour une reconnaissance centrée sur certaines langues, utilisez `SpeechConfig(sttModel = SttModel.PARAKEET, languageHints = listOf("en", "fr"))`. Définissez `language = "en"` pour fixer une seule langue.

**Supertonic-3** est une synthèse vocale multilingue de meilleure qualité, activable en option — sélectionnez-la avec `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (nécessite le backend LiteRT). L'hôte exécute ses quatre graphes de flow-matching non autorégressifs en local à 44,1 kHz ; le front-end est G2P-free (NFKD + index Unicode — aucun phonémiseur), de sorte que les 31 langues passent par un seul chemin.

## Essayer la démo

Téléchargez l'[APK signé](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) et installez-le sur n'importe quel appareil Android arm64 (8+). Le bundle de modèles faible mémoire par défaut (~500 Mo) est téléchargé automatiquement au premier lancement.

## Ajouter la dépendance

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.15")
}
```

## Utilisation Kotlin

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

// Alimente avec du PCM float32 mono 16 kHz depuis le micro
pipeline.pushAudio(samples)
```

## Compiler depuis les sources

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 tests e2e
```

`./setup.sh` initialise le sous-module speech-core et télécharge ONNX Runtime
dans `./ort/`.

## Application de démo

Le module [`app/`](app/) est une démo minimale d'assistant vocal avec :

- Visualisation de la forme d'onde VAD en temps réel
- Mode écho : transcrit la voix et la synthétise en retour (sans LLM)
- Mode dictée : résultats partiels en streaming
- Superposition vocale : bouton micro flottant pour dicter dans n'importe quelle app
- STT Parakeet TDT à 114 langues dans les écrans écho et dictée
- Écran de test `SpeechRecognizer` — exerce le chemin d'entrée vocale à l'échelle du système
- Interface de bulles de chat avec affichage de la latence STT/TTS

```bash
./gradlew :app:installDebug
```

### Superposition vocale (dicter dans n'importe quelle app)

La **superposition vocale** place un bouton micro déplaçable au-dessus des
autres apps. Un appui le transforme en **■ arrêter** / **✕ annuler** : arrêter
écrit la transcription dans le champ de texte qui a le focus, annuler la jette.
Si aucun champ éditable n'a le focus, le texte part dans le presse-papiers au
lieu d'être perdu.

Trois autorisations sont nécessaires, chacune avec son propre écran système —
l'écran de configuration indique celles qui manquent :

| Autorisation | Pourquoi |
| --- | --- |
| Micro | capturer l'audio |
| Affichage par-dessus les autres apps | dessiner le bouton hors de l'app |
| Service d'accessibilité | écrire dans le champ de texte d'une autre app |

La fenêtre de superposition est délibérément non focalisable, afin que le champ
cible conserve le focus de saisie pendant l'appui sur les boutons. Le texte est
inséré au curseur via `ACTION_SET_TEXT`. Les champs dont le contenu réel est
illisible — certaines apps annoncent leur texte indicatif comme le texte du
champ lui-même — sont remplis par un collage, ce qui remplace ce qui se
trouvait dans le presse-papiers ; la dictée en est effacée juste après.

> Installation depuis un APK plutôt que le Play Store ? Android bloque le
> commutateur d'accessibilité tant qu'il n'est pas autorisé dans
> Paramètres → Applications → Speech → ⋮ → **Autoriser les paramètres
> restreints**.

### Démo de contrôle du pipeline complet

L'app séparée [`control-demo/`](control-demo/) exécute tout l'agent localement :
Silero VAD → Parakeet-EOU STT → appels d'outils FunctionGemma 270M → actions sur
l'appareil Android → Pocket TTS. Elle affiche la latence de chaque étape et se
lie directement au `:sdk` de ce checkout, afin d'utiliser les optimisations
vocales locales.

Téléchargez l'[APK signé de Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
depuis la dernière version, ou installez un build de développement depuis les sources :

```bash
./gradlew :control-demo:installDebug
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

Mesuré sur un Galaxy S23 Ultra (SM-S918B), CPU seul sauf indication. Le RTF est le
temps mural ÷ la durée audio émise : plus bas est plus rapide, et <1,0 est plus rapide que le temps réel.

| Modèle | Tâche | RTF | Latence | Mémoire max |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | STT streaming + EOU | 0,21 | partiels streaming | 232 Mo |
| Kokoro 82M graphe complet (publié, CPU à deux threads) | TTS | 1,81 | par phrase | ~604 Mo |
| Kokoro 82M tour court (graphe de 3,0 s, par défaut) | TTS | 0,75–0,88 | réponses bornées ; nouvelle tentative sûre | ~527 Mo |
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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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
