# Speech Android

📖 Языки: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Локальный речевой SDK для Android, основанный на [ONNX Runtime](https://onnxruntime.ai) и [speech-core](https://github.com/soniqo/speech-core).

Распознавание речи (114 языков), синтез речи (8 языков), определение голосовой активности и шумоподавление — всё работает локально. Никаких облачных API, никакие данные не покидают устройство.

**[Демо APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Модели](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (аналог для Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (движок конвейера + сборка для Linux/встраиваемых систем)

## Область применения

Этот репозиторий — **Android-обёртка**: Kotlin SDK, JNI-мост, демо-приложение. C++-движок и обёртки ONNX-моделей (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) находятся в [speech-core](https://github.com/soniqo/speech-core) и подключаются через git-submodule. Linux / автомобильные системы (Yocto, Qualcomm SA8295P/SA8255P) — в [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Модели

| Модель | Задача | Размер INT8 | Языки |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | Распознавание речи | 891 МБ | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Синтез речи | 330 МБ | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Определение голосовой активности | 2 МБ | Любой |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Шумоподавление | ~8 МБ | Любой |

Модели загружаются автоматически при первом запуске через `ModelManager.ensureModels()`.

## Попробовать демо

Скачайте [подписанный APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) и установите на любое arm64-устройство Android (8+). Модели (~1,2 ГБ) загружаются автоматически при первом запуске.

## Добавить зависимость

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Использование Kotlin

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

## Сборка из исходного кода

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 e2e-теста
```

`./setup.sh` инициализирует submodule speech-core и загружает ONNX Runtime
в `./ort/`.

## Демо-приложение

Модуль [`app/`](app/) — минимальное демо голосового ассистента, включающее:

- Визуализацию формы волны VAD в реальном времени
- Эхо-режим: транскрибирует речь и синтезирует её обратно (без LLM)
- Режим диктовки: потоковые частичные результаты
- Тестовый экран `SpeechRecognizer` — задействует системный путь голосового ввода
- UI с пузырями чата и отображением задержки STT/TTS

```bash
./gradlew :app:installDebug
```

## Системный голосовой ввод (`RecognitionService`)

SDK включает готовый к использованию `audio.soniqo.speech.service.SpeechRecognitionService`, который подключается к API `SpeechRecognizer` фреймворка Android — никакого кода писать не нужно. Как только ваше приложение выбрано в качестве распознавателя голоса по умолчанию, любое стороннее приложение, вызывающее `SpeechRecognizer.createSpeechRecognizer(context)` (без `ComponentName`), получает полностью локальный STT через ваш конвейер.

**1. Объявите `RECORD_AUDIO` и сервис в `AndroidManifest.xml`:**

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

**2. Добавьте `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Опционально добавьте `android:settingsActivity="..."`, чтобы отобразить иконку шестерёнки в системном выборе голосового ввода.)

**3. Установите сервис по умолчанию в системе** (Настройки → Система → Языки и ввод → Выбор голосового ввода на стоковом Android, или через adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Проверьте**, запустив экран *Recognizer test* в демо-приложении, который вызывает `SpeechRecognizer.createSpeechRecognizer(ctx)` (без компонента) и логирует каждый callback фреймворка — удобно для подтверждения binder round-trip без необходимости в logcat.

Сервис реализует `onCheckRecognitionSupport` (API 33+), возвращающий 27 BCP-47 языков, поддерживаемых Parakeet TDT v3, помеченных как `installedOnDeviceLanguage` при наличии моделей (или `pendingOnDeviceLanguage` во время загрузки). Аудиофокус удерживается с `AUDIOFOCUS_GAIN_TRANSIENT` на время сессии.

**Оговорка:** Gboard, Samsung Keyboard и Google Assistant поставляются с собственными распознавателями и обходят системное значение по умолчанию. Через ваш сервис проходят только те приложения, которые явно вызывают API `SpeechRecognizer` фреймворка (или строят на нём собственный UI).

## Производительность

Измерено на эмуляторе Android (arm64-v8a, без NNAPI). Реальное оборудование значительно быстрее.

| Модель | Задача | Аудио | Инференс | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5 с | 175 мс | 0,12 |
| Kokoro 82M | TTS | 1,9 с вывод | 1075 мс | 0,58 |
| Silero VAD v5 | VAD | блок 32 мс | <1 мс | <0,01 |

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
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 строк)            │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (git submodule)  │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (оркестрация:          │    │
│  │   pipeline · turn · прерывания)      │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Каждый класс модели напрямую реализует соответствующий интерфейс speech-core
(`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) —
JNI-мост создаёт их и передаёт ссылки в `VoicePipeline`. Никаких шаблонных
обвязок через C-vtable.

## Аппаратное ускорение

| Чипсет | Ускорение |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| Резерв CPU | XNNPACK |

Для автомобильных Qualcomm SA8295P / SA8255P с QNN (Hexagon DSP) см.
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Связанные проекты

| Репозиторий | Область |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Кроссплатформенный движок конвейера на C++ + обёртки ONNX-моделей + примеры для Linux/встраиваемых систем |
| **speech-android** | Android-обёртка — Kotlin SDK + JNI-мост поверх speech-core |

## Лицензия

Apache 2.0
