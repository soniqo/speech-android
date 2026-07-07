# Speech Android

📖 भाषाएँ: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Android के लिए ऑन-डिवाइस स्पीच SDK, [ONNX Runtime](https://onnxruntime.ai) और [speech-core](https://github.com/soniqo/speech-core) द्वारा संचालित।

कम-मेमोरी स्ट्रीमिंग स्पीच रिकग्निशन (डिफ़ॉल्ट 25 भाषाएँ, 114-भाषा TDT वैकल्पिक), टेक्स्ट-टू-स्पीच, वॉयस एक्टिविटी डिटेक्शन, और शोर रद्दीकरण — सभी स्थानीय रूप से चलते हैं। कोई क्लाउड API नहीं, कोई डेटा डिवाइस से बाहर नहीं जाता।

**[डेमो APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[मॉडल](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple समकक्ष) · **[speech-core](https://github.com/soniqo/speech-core)** (पाइपलाइन इंजन + Linux/एम्बेडेड बिल्ड)

## स्कोप

यह रिपॉज़िटरी **Android पैकेजिंग** है: Kotlin SDK, JNI ब्रिज, डेमो ऐप। C++ इंजन और ONNX मॉडल रैपर (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) [speech-core](https://github.com/soniqo/speech-core) में रहते हैं और एक git सबमॉड्यूल के माध्यम से शामिल किए जाते हैं। Linux / ऑटोमोटिव (Yocto, Qualcomm SA8295P/SA8255P) [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) पर रहता है।

## मॉडल

| मॉडल | कार्य | डाउनलोड | पीक मेमोरी | भाषाएँ |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | स्ट्रीमिंग STT + EOU (डिफ़ॉल्ट) | 153 MB | 232 MB | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | व्यापक STT (वैकल्पिक) | 891 MB | ~1.1-1.3 GB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | टेक्स्ट-टू-स्पीच (डिफ़ॉल्ट) | 330 MB | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | टेक्स्ट-टू-स्पीच (LiteRT, फ़्लो-मैचिंग, G2P-free, 44.1 kHz) | ~380 MB | 832 MB | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | वॉयस एक्टिविटी डिटेक्शन | 2 MB | <10 MB | कोई भी |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | शोर रद्दीकरण | ~8 MB | डिफ़ॉल्ट रूप से लोड नहीं | कोई भी |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | ऑन-डिवाइस LLM — संरचित फ़ंक्शन / टूल कॉल | 283 MB | ऐप runtime पर निर्भर | EN-tuned |

मॉडल पहले लॉन्च पर `ModelManager.ensureModels()` के माध्यम से स्वचालित रूप से डाउनलोड होते हैं।

`SpeechConfig()` डिफ़ॉल्ट रूप से `SttModel.PARAKEET_EOU` और `TtsModel.KOKORO` इस्तेमाल करता है, ताकि डेमो और सिस्टम रिकग्नाइज़र कम-मेमोरी Android पथ पर रहें। बड़े 114-भाषा TDT मॉडल की ज़रूरत होने पर ही `SpeechConfig(sttModel = SttModel.PARAKEET)` इस्तेमाल करें।

**Supertonic-3** एक ऑप्ट-इन उच्च-गुणवत्ता वाला बहुभाषी TTS है — इसे `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` के साथ चुनें (LiteRT बैकएंड आवश्यक)। होस्ट इसके चार नॉन-ऑटोरिग्रेसिव फ़्लो-मैचिंग ग्राफ़ ऑन-डिवाइस 44.1 kHz पर चलाता है; फ़्रंट-एंड G2P-free है (NFKD + Unicode इंडेक्स — कोई फ़ोनेमाइज़र नहीं), इसलिए सभी 31 भाषाएँ एक ही पथ से होकर गुजरती हैं।

## डेमो आज़माएँ

[हस्ताक्षरित APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) डाउनलोड करें और किसी भी arm64 Android डिवाइस (8+) पर इंस्टॉल करें। डिफ़ॉल्ट कम-मेमोरी मॉडल बंडल (~500 MB) पहले लॉन्च पर स्वचालित रूप से डाउनलोड होता है।

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

सेवा `onCheckRecognitionSupport` (API 33+) को लागू करती है जो Parakeet-EOU द्वारा कवर की गई 25 BCP-47 आधार भाषाएँ लौटाती है, और समर्थित आधार भाषा से मेल खाने पर अनुरोधित सटीक क्षेत्रीय टैग भी लौटाती है। मॉडल मौजूद होने पर भाषाएँ `installedOnDeviceLanguage` के रूप में, और डाउनलोड से पहले `supportedOnDeviceLanguage` के रूप में चिह्नित होती हैं। सेवा कॉल करने वाले ऐप से ऑडियो फोकस नहीं लेती।

**सीमा:** Gboard, Samsung Keyboard और Google Assistant अपने स्वयं के पहचानकर्ता बंडल करते हैं और सिस्टम डिफ़ॉल्ट को छोड़ देते हैं। फ्रेमवर्क `SpeechRecognizer` API को स्पष्ट रूप से कॉल करने वाले ऐप (या उसके ऊपर अपना UI बनाने वाले) ही आपकी सेवा से होकर गुजरते हैं।

## सिस्टम टेक्स्ट-टू-स्पीच (`TextToSpeechService`)

डेमो ऐप `audio.soniqo.speech.service.SpeechTextToSpeechService` भी उजागर करता है, इसलिए Android सेटिंग्स → सिस्टम → भाषाएँ और इनपुट → टेक्स्ट-टू-स्पीच आउटपुट में इस ऐप को चुन सकता है। यह पथ `ModelManager.ensureTtsModels()` और अलग `models_tts/` कैश का उपयोग करता है, इसलिए फ्रेमवर्क TTS पूर्ण VAD/STT/enhancer पाइपलाइन बंडल के बजाय केवल Kokoro assets डाउनलोड करता है।

किसी अन्य ऐप से इंजन उजागर करने के लिए सेवा घोषित करें:

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

`app/src/main/res/xml/tts_engine.xml` जोड़ें:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## प्रदर्शन

Android एमुलेटर (arm64-v8a, NNAPI के बिना) पर मापा गया। वास्तविक हार्डवेयर काफी तेज़ है।

Galaxy S23 Android पर मापा गया, जब तक अलग से न कहा गया हो CPU-only। कम RTF तेज़ है।

| मॉडल | कार्य | RTF | लेटेंसी | पीक मेमोरी |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | स्ट्रीमिंग STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M ONNX FP32 | TTS | 0.53 | वाक्य-स्तर | 640 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1 सेकंड TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | हर 32 मिलीसेकंड चंक पर <1 मिलीसेकंड | <10 MB |

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
