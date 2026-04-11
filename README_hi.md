# Speech Android

📖 भाषाएँ: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Android और एम्बेडेड Linux के लिए ऑन-डिवाइस स्पीच SDK, [ONNX Runtime](https://onnxruntime.ai) और [speech-core](https://github.com/soniqo/speech-core) द्वारा संचालित।

स्पीच रिकग्निशन (114 भाषाएँ), टेक्स्ट-टू-स्पीच (8 भाषाएँ), वॉयस एक्टिविटी डिटेक्शन, और शोर रद्दीकरण — सभी स्थानीय रूप से चलते हैं। कोई क्लाउड API नहीं, कोई डेटा डिवाइस से बाहर नहीं जाता।

**[डेमो APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[मॉडल](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple समकक्ष) · **[speech-core](https://github.com/soniqo/speech-core)** (पाइपलाइन इंजन)

## प्लेटफ़ॉर्म

| प्लेटफ़ॉर्म | API | त्वरण | निर्देशिका |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| एम्बेडेड Linux | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## मॉडल

| मॉडल | कार्य | INT8 आकार | भाषाएँ |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | स्पीच रिकग्निशन | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | टेक्स्ट-टू-स्पीच | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | वॉयस एक्टिविटी डिटेक्शन | 2 MB | कोई भी |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | शोर रद्दीकरण | ~8 MB | कोई भी |

मॉडल पहले लॉन्च पर स्वचालित रूप से डाउनलोड होते हैं (Android) या मैन्युअल रूप से रखे जाते हैं (Linux)।

## Android

### डेमो आज़माएँ

[हस्ताक्षरित APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) डाउनलोड करें और किसी भी arm64 Android डिवाइस (8+) पर इंस्टॉल करें। मॉडल (~1.2 GB) पहले लॉन्च पर स्वचालित रूप से डाउनलोड होते हैं।

### निर्भरता जोड़ें

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Kotlin उपयोग

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

### स्रोत से बिल्ड करें

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e परीक्षण
```

### डेमो ऐप

[`app/`](app/) मॉड्यूल एक न्यूनतम वॉयस असिस्टेंट डेमो है जिसमें शामिल हैं:

- रीयल-टाइम VAD वेवफ़ॉर्म विज़ुअलाइज़ेशन
- इको मोड: स्पीच को ट्रांसक्राइब करता है और इसे वापस सिंथेसाइज़ करता है (कोई LLM नहीं)
- STT/TTS विलंबता प्रदर्शन के साथ चैट बबल UI

```bash
./gradlew :app:installDebug
```

## प्रदर्शन

Android एमुलेटर (arm64-v8a, NNAPI के बिना) पर मापा गया। वास्तविक हार्डवेयर काफी तेज़ है।

| मॉडल | कार्य | ऑडियो | अनुमान | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 सेकंड | 175 मिलीसेकंड | 0.12 |
| Kokoro 82M | TTS | 1.9 सेकंड आउटपुट | 1,075 मिलीसेकंड | 0.58 |
| Silero VAD v5 | VAD | 32 मिलीसेकंड चंक | <1 मिलीसेकंड | <0.01 |

## एम्बेडेड Linux

ऑटोमोटिव और एम्बेडेड प्लेटफ़ॉर्म के लिए न्यूनतम C API। पूर्ण दस्तावेज़ के लिए [`linux/README.md`](linux/README.md) देखें।

### C API उपयोग

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon DSP त्वरण

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### बिल्ड

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### परीक्षण

```bash
linux/tests/download_models.sh              # ONNX मॉडल डाउनलोड करें
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 परीक्षण
```

### Yocto के लिए क्रॉस-कंपाइल

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

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

## हार्डवेयर त्वरण

| प्लेटफ़ॉर्म | चिपसेट | त्वरण |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| ऑटोमोटिव | SA8295P / SA8255P | QNN → Hexagon DSP |
| कोई भी | CPU फ़ॉलबैक | XNNPACK |

## संबंधित परियोजनाएँ

| रिपॉज़िटरी | प्लेटफ़ॉर्म |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | क्रॉस-प्लेटफ़ॉर्म C++ पाइपलाइन इंजन |
| **speech-android** | Android + एम्बेडेड Linux — ONNX Runtime |

## लाइसेंस

Apache 2.0
