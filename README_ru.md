# Speech Android

📖 Языки: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Локальный речевой SDK для Android, основанный на [ONNX Runtime](https://onnxruntime.ai) и [speech-core](https://github.com/soniqo/speech-core).

Потоковое распознавание речи с низким потреблением памяти (25 языков по умолчанию, TDT на 114 языков опционально), синтез речи, определение голосовой активности и шумоподавление — всё работает локально. Никаких облачных API, никакие данные не покидают устройство.

**[📚 Документация Android](https://soniqo.audio/ru/getting-started/android)**

**[Демо APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[APK Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Модели](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (аналог для Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (движок конвейера + сборка для Linux/встраиваемых систем)

## Демонстрация

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="Полностью офлайновый голосовой агент в 1,2 ГБ на Android — смотреть демо на YouTube">
  </a>
</p>
<p align="center"><em>Полный командный цикл <a href="control-demo/">control-demo</a> — Silero VAD → Parakeet STT → FunctionGemma → действие на устройстве → ответ Pocket TTS — полностью офлайн в 1,2 ГБ RAM</em></p>

## Область применения

Этот репозиторий — **Android-обёртка**: Kotlin SDK, JNI-мост, демо-приложение. C++-движок и обёртки ONNX-моделей (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) находятся в [speech-core](https://github.com/soniqo/speech-core) и подключаются через git-submodule. Linux / автомобильные системы (Yocto, Qualcomm SA8295P/SA8255P) — в [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Модели

| Модель | Задача | Загрузка | Пиковая память | Языки |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/ru/guides/dictate) | Потоковый STT + EOU (по умолчанию) | [153 МБ](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 МБ | 25 |
| [Parakeet TDT v3](https://soniqo.audio/ru/guides/parakeet/android) | STT с широким покрытием (опционально) | [891 МБ](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 ГБ | 114 |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Офлайн STT + перевод (опционально) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ещё не измерено | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/ru/guides/kokoro/android) | Синтез речи (по умолчанию) | [330 МБ](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 МБ | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Потоковый синтез речи (опционально, фиксированный голос Alba) | ~126 МБ | ещё не измерено | Английский |
| [Supertonic-3](https://soniqo.audio/ru/guides/supertonic) | Синтез речи (LiteRT, flow-matching, G2P-free, 44,1 кГц) | [~380 МБ](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 МБ | 31 |
| [Silero VAD v5](https://soniqo.audio/ru/guides/vad/android) | Определение голосовой активности | [2 МБ](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 МБ | Любой |
| [DeepFilterNet3](https://soniqo.audio/ru/guides/denoise/android) | Шумоподавление | [~8 МБ](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | по умолчанию не загружается | Любой |
| [FunctionGemma 270M](https://soniqo.audio/ru/guides/function-calls) | Локальная LLM — структурированные вызовы функций / инструментов | [283 МБ](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | зависит от runtime приложения | EN-tuned |

Модели загружаются автоматически при первом запуске через `ModelManager.ensureModels()`.

`SpeechConfig()` по умолчанию использует `SttModel.PARAKEET_EOU` и `TtsModel.KOKORO_SHORT_TURN`, чтобы интеграции SDK и системный распознаватель работали по низкопамятному Android-пути. Демо-приложение выбирает `SttModel.PARAKEET`, поэтому экраны эха и диктовки используют более крупную TDT-модель на 114 языков.

Для распознавания с фокусом на языки используйте `SpeechConfig(sttModel = SttModel.PARAKEET, languageHints = listOf("en", "fr"))`. Задайте `language = "en"`, если нужно зафиксировать один язык.

**Supertonic-3** — это опциональный многоязычный TTS повышенного качества: выберите его через `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (требуется бэкенд LiteRT). Хост выполняет его четыре неавторегрессионных flow-matching-графа на устройстве на частоте 44,1 кГц; фронтенд работает G2P-free (NFKD + индекс Unicode — без фонемизатора), поэтому все 31 язык проходят через один путь.

## Попробовать демо

Скачайте [подписанный APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) и установите на любое arm64-устройство Android (8+). Стандартный низкопамятный набор моделей (~500 МБ) загружается автоматически при первом запуске.

## Добавить зависимость

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.13")
}
```

## Использование Kotlin

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

// Подавайте 16 кГц моно float32 PCM с микрофона
pipeline.pushAudio(samples)
```

## Сборка из исходного кода

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 e2e-тестов
```

`./setup.sh` инициализирует submodule speech-core и загружает ONNX Runtime
в `./ort/`.

## Демо-приложение

Модуль [`app/`](app/) — минимальное демо голосового ассистента, включающее:

- Визуализацию формы волны VAD в реальном времени
- Эхо-режим: транскрибирует речь и синтезирует её обратно (без LLM)
- Режим диктовки: потоковые частичные результаты
- Parakeet TDT STT на 114 языков в экранах эха и диктовки
- Тестовый экран `SpeechRecognizer` — задействует системный путь голосового ввода
- UI с пузырями чата и отображением задержки STT/TTS

```bash
./gradlew :app:installDebug
```

### Демо полного конвейера управления

Отдельное приложение [`control-demo/`](control-demo/) локально запускает
полного агента: Silero VAD → Parakeet-EOU STT → вызовы инструментов
FunctionGemma 270M → действия Android-устройства → Pocket TTS. Оно показывает
задержку каждого этапа и напрямую подключает `:sdk` из этой рабочей копии,
поэтому использует локальные оптимизации речи.

Скачайте [подписанный APK Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
из последнего релиза или установите сборку для разработки из исходного кода:

```bash
./gradlew :control-demo:installDebug
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

Сервис реализует `onCheckRecognitionSupport` (API 33+), возвращающий 25 базовых BCP-47 языков, поддерживаемых Parakeet-EOU, а также точный запрошенный региональный тег, если он соответствует поддерживаемому базовому языку. Языки помечаются как `installedOnDeviceLanguage`, когда модели уже есть на устройстве, или как `supportedOnDeviceLanguage` до загрузки. Сервис не забирает аудиофокус у вызывающего приложения.

**Оговорка:** Gboard, Samsung Keyboard и Google Assistant поставляются с собственными распознавателями и обходят системное значение по умолчанию. Через ваш сервис проходят только те приложения, которые явно вызывают API `SpeechRecognizer` фреймворка (или строят на нём собственный UI).

## Системный синтез речи (`TextToSpeechService`)

Демо-приложение также публикует `audio.soniqo.speech.service.SpeechTextToSpeechService`, поэтому Android может выбрать приложение в Настройки → Система → Языки и ввод → Синтез речи. Этот путь использует `ModelManager.ensureTtsModels()` и отдельный кэш `models_tts/`, поэтому framework TTS загружает только ресурсы Kokoro, а не полный пакет VAD/STT/enhancer.

Чтобы открыть движок из другого приложения, объявите сервис:

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

Добавьте `app/src/main/res/xml/tts_engine.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Производительность

Измерено на Galaxy S23 Ultra (SM-S918B), только CPU если не указано иное. RTF — это
настенное время ÷ длительность выданного аудио: чем ниже, тем быстрее; <1,0 быстрее реального времени.

| Модель | Задача | RTF | Задержка | Пиковая память |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | Потоковый STT + EOU | 0,21 | потоковые partials | 232 МБ |
| Kokoro 82M полный граф (опубликованный, CPU с двумя потоками) | TTS | 1,81 | по предложениям | ~604 МБ |
| Kokoro 82M короткий ход (граф 3,0 с, по умолчанию) | TTS | 0,75–0,88 | ограниченные ответы; безопасный повтор | ~527 МБ |
| Supertonic-3 LiteRT | TTS | 0,34 | ~1,1 с TTFA | 832 МБ |
| Silero VAD v5 | VAD | <0,01 | <1 мс на блок 32 мс | <10 МБ |

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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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
