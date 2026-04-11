# Speech Android

📖 Языки: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Речевой SDK для устройств Android и встраиваемого Linux, основанный на [ONNX Runtime](https://onnxruntime.ai) и [speech-core](https://github.com/soniqo/speech-core).

Распознавание речи (114 языков), синтез речи (8 языков), определение голосовой активности и шумоподавление — всё работает локально. Никаких облачных API, никакие данные не покидают устройство.

**[Демо APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Модели](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (аналог для Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (движок конвейера)

## Платформы

| Платформа | API | Ускорение | Каталог |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Встраиваемый Linux | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Модели

| Модель | Задача | Размер INT8 | Языки |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Распознавание речи | 891 МБ | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Синтез речи | 330 МБ | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Определение голосовой активности | 2 МБ | Любой |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Шумоподавление | ~8 МБ | Любой |

Модели загружаются автоматически при первом запуске (Android) или размещаются вручную (Linux).

## Android

### Попробовать демо

Скачайте [подписанный APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) и установите на любое arm64-устройство Android (8+). Модели (~1,2 ГБ) загружаются автоматически при первом запуске.

### Добавить зависимость

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Использование Kotlin

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

// Подавайте 16 кГц моно float32 PCM с микрофона
pipeline.pushAudio(samples)
```

### Сборка из исходного кода

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e-теста
```

### Демо-приложение

Модуль [`app/`](app/) — минимальное демо голосового ассистента, включающее:

- Визуализацию формы волны VAD в реальном времени
- Эхо-режим: транскрибирует речь и синтезирует её обратно (без LLM)
- UI с пузырями чата и отображением задержки STT/TTS

```bash
./gradlew :app:installDebug
```

## Производительность

Измерено на эмуляторе Android (arm64-v8a, без NNAPI). Реальное оборудование значительно быстрее.

| Модель | Задача | Аудио | Инференс | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5 с | 175 мс | 0,12 |
| Kokoro 82M | TTS | 1,9 с вывод | 1075 мс | 0,58 |
| Silero VAD v5 | VAD | блок 32 мс | <1 мс | <0,01 |

## Встраиваемый Linux

Минимальный C API для автомобильных и встраиваемых платформ. Полную документацию см. в [`linux/README.md`](linux/README.md).

### Использование C API

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Ускорение Hexagon DSP

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### Сборка

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### Тесты

```bash
linux/tests/download_models.sh              # загрузить модели ONNX
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 тестов
```

### Кросс-компиляция для Yocto

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

## Конвейер

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Поддерживается прерывание (barge-in): речь во время воспроизведения TTS прерывает его и начинает новую транскрипцию.

## Архитектура

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

## Аппаратное ускорение

| Платформа | Чипсет | Ускорение |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Автомобильная | SA8295P / SA8255P | QNN → Hexagon DSP |
| Любая | Резерв CPU | XNNPACK |

## Связанные проекты

| Репозиторий | Платформа |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Кроссплатформенный движок конвейера на C++ |
| **speech-android** | Android + встраиваемый Linux — ONNX Runtime |

## Лицензия

Apache 2.0
