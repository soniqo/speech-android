# Speech Android

📖 भाषाएँ: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Android के लिए ऑन-डिवाइस स्पीच SDK, [ONNX Runtime](https://onnxruntime.ai) और [speech-core](https://github.com/soniqo/speech-core) द्वारा संचालित।

स्पीच रिकग्निशन (114 भाषाएँ), टेक्स्ट-टू-स्पीच (8 भाषाएँ), वॉयस एक्टिविटी डिटेक्शन, और शोर रद्दीकरण — सभी स्थानीय रूप से चलते हैं। कोई क्लाउड API नहीं, कोई डेटा डिवाइस से बाहर नहीं जाता।

**[डेमो APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[मॉडल](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple समकक्ष) · **[speech-core](https://github.com/soniqo/speech-core)** (पाइपलाइन इंजन + Linux/एम्बेडेड बिल्ड)

## स्कोप

यह रिपॉज़िटरी **Android पैकेजिंग** है: Kotlin SDK, JNI ब्रिज, डेमो ऐप। C++ इंजन और ONNX मॉडल रैपर (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) [speech-core](https://github.com/soniqo/speech-core) में रहते हैं और एक git सबमॉड्यूल के माध्यम से शामिल किए जाते हैं। Linux / ऑटोमोटिव (Yocto, Qualcomm SA8295P/SA8255P) [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) पर रहता है।

## मॉडल

| मॉडल | कार्य | INT8 आकार | भाषाएँ |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | स्पीच रिकग्निशन | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | टेक्स्ट-टू-स्पीच | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | वॉयस एक्टिविटी डिटेक्शन | 2 MB | कोई भी |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | शोर रद्दीकरण | ~8 MB | कोई भी |

मॉडल पहले लॉन्च पर `ModelManager.ensureModels()` के माध्यम से स्वचालित रूप से डाउनलोड होते हैं।

## डेमो आज़माएँ

[हस्ताक्षरित APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) डाउनलोड करें और किसी भी arm64 Android डिवाइस (8+) पर इंस्टॉल करें। मॉडल (~1.2 GB) पहले लॉन्च पर स्वचालित रूप से डाउनलोड होते हैं।

## निर्भरता जोड़ें

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin उपयोग

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

// माइक्रोफ़ोन से 16kHz मोनो float32 PCM फ़ीड करें
pipeline.pushAudio(samples)
```

## स्रोत से बिल्ड करें

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e परीक्षण
```

`./setup.sh` speech-core सबमॉड्यूल को इनिशियलाइज़ करता है और ONNX Runtime को
`./ort/` में डाउनलोड करता है।

## डेमो ऐप

[`app/`](app/) मॉड्यूल एक न्यूनतम वॉयस असिस्टेंट डेमो है जिसमें शामिल हैं:

- रीयल-टाइम VAD वेवफ़ॉर्म विज़ुअलाइज़ेशन
- इको मोड: स्पीच को ट्रांसक्राइब करता है और इसे वापस सिंथेसाइज़ करता है (कोई LLM नहीं)
- डिक्टेशन मोड: स्ट्रीमिंग आंशिक परिणाम
- `SpeechRecognizer` टेस्ट स्क्रीन — सिस्टम-वाइड वॉयस इनपुट पथ का परीक्षण करता है
- STT/TTS विलंबता प्रदर्शन के साथ चैट बबल UI

```bash
./gradlew :app:installDebug
```

## सिस्टम वॉयस इनपुट (`RecognitionService`)

SDK एक उपयोग के लिए तैयार `audio.soniqo.speech.service.SpeechRecognitionService` शामिल करता है जो Android फ्रेमवर्क के `SpeechRecognizer` API से जुड़ता है — कोई कोड लिखने की आवश्यकता नहीं। एक बार आपका ऐप डिफ़ॉल्ट वॉयस रिकग्नाइज़र के रूप में चुना जाता है, कोई भी थर्ड-पार्टी ऐप जो `SpeechRecognizer.createSpeechRecognizer(context)` (बिना `ComponentName` के) कॉल करता है, आपकी पाइपलाइन के माध्यम से पूरी तरह से ऑन-डिवाइस STT प्राप्त करता है।

**1. `AndroidManifest.xml` में `RECORD_AUDIO` और सेवा घोषित करें:**

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

**2. `app/src/main/res/xml/recognition_service.xml` जोड़ें:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(वैकल्पिक रूप से `android:settingsActivity="..."` जोड़ें ताकि सिस्टम वॉयस-इनपुट पिकर में एक गियर आइकन दिखे।)

**3. सेवा को सिस्टम डिफ़ॉल्ट के रूप में सेट करें** (स्टॉक Android पर सेटिंग्स → सिस्टम → भाषाएँ और इनपुट → वॉयस इनपुट पिकर, या adb के माध्यम से):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. सत्यापित करें** डेमो ऐप का *Recognizer test* स्क्रीन चलाकर, जो `SpeechRecognizer.createSpeechRecognizer(ctx)` (बिना कंपोनेंट के) कॉल करता है और हर फ्रेमवर्क कॉलबैक को लॉग करता है — logcat के बिना binder राउंड-ट्रिप की पुष्टि के लिए उपयोगी।

सेवा `onCheckRecognitionSupport` (API 33+) को लागू करती है जो Parakeet TDT v3 द्वारा कवर की गई 27 BCP-47 भाषाओं को लौटाती है, मॉडल मौजूद होने पर `installedOnDeviceLanguage` के रूप में चिह्नित (या डाउनलोड के दौरान `pendingOnDeviceLanguage`)। सत्र की अवधि के लिए `AUDIOFOCUS_GAIN_TRANSIENT` के साथ ऑडियो फोकस प्राप्त किया जाता है।

**सीमा:** Gboard, Samsung Keyboard और Google Assistant अपने स्वयं के पहचानकर्ता बंडल करते हैं और सिस्टम डिफ़ॉल्ट को छोड़ देते हैं। फ्रेमवर्क `SpeechRecognizer` API को स्पष्ट रूप से कॉल करने वाले ऐप (या उसके ऊपर अपना UI बनाने वाले) ही आपकी सेवा से होकर गुजरते हैं।

## प्रदर्शन

Android एमुलेटर (arm64-v8a, NNAPI के बिना) पर मापा गया। वास्तविक हार्डवेयर काफी तेज़ है।

| मॉडल | कार्य | ऑडियो | अनुमान | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 सेकंड | 175 मिलीसेकंड | 0.12 |
| Kokoro 82M | TTS | 1.9 सेकंड आउटपुट | 1,075 मिलीसेकंड | 0.58 |
| Silero VAD v5 | VAD | 32 मिलीसेकंड चंक | <1 मिलीसेकंड | <0.01 |

## पाइपलाइन

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

बार्ज-इन समर्थित: TTS प्लेबैक के दौरान बोलना उसे बाधित करता है और एक नया ट्रांसक्रिप्शन शुरू करता है।

## आर्किटेक्चर

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
│  │   KokoroTts / DeepFilterEnhancer     │    │
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

प्रत्येक मॉडल क्लास सीधे संबंधित speech-core इंटरफ़ेस (`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) को लागू करता है — JNI ब्रिज उन्हें इंस्टैंशिएट करता है और संदर्भ `VoicePipeline` को सौंपता है। कोई C-vtable अडैप्टर बॉइलरप्लेट नहीं।

## हार्डवेयर त्वरण

| चिपसेट | त्वरण |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU फ़ॉलबैक | XNNPACK |

ऑटोमोटिव Qualcomm SA8295P / SA8255P के लिए QNN (Hexagon DSP) के साथ, [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) देखें।

## संबंधित परियोजनाएँ

| रिपॉज़िटरी | स्कोप |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | क्रॉस-प्लेटफ़ॉर्म C++ पाइपलाइन इंजन + ONNX मॉडल रैपर + Linux/एम्बेडेड उदाहरण |
| **speech-android** | Android रैपर — speech-core के ऊपर Kotlin SDK + JNI ब्रिज |

## लाइसेंस

Apache 2.0
