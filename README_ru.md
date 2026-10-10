# Speech Android

📖 Языки: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Локальный речевой SDK для Android, основанный на [ONNX Runtime](https://onnxruntime.ai) и [speech-core](https://github.com/soniqo/speech-core).

Потоковое распознавание речи с низким потреблением памяти и опциональная более крупная TDT-модель, обе для 25 европейских языков, а также синтез речи, определение голосовой активности и шумоподавление — всё работает локально. Никаких облачных API, никакие данные не покидают устройство.

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
| [Parakeet TDT v3](https://soniqo.audio/ru/guides/parakeet/android) | STT с широким покрытием (опционально) | [891 МБ](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 ГБ | 25 европейских |
| [Nemotron-3.5 multilingual](https://soniqo.audio/ru/guides/nemotron) | Потоковый STT с управлением через промпт (опционально) | [~721 МБ](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | ещё не измерено | более 100 (включая zh) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Офлайн STT + перевод (опционально) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/ru/guides/kokoro/android) | Синтез речи (по умолчанию) | [330 МБ](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 МБ | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Потоковый синтез речи (опционально, фиксированный голос Alba) | ~126 МБ | ещё не измерено | Английский |
| [Supertonic-3](https://soniqo.audio/ru/guides/supertonic) | Синтез речи (LiteRT, flow-matching, G2P-free, 44,1 кГц) | [~380 МБ](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 МБ | 31 |
| [Silero VAD v5](https://soniqo.audio/ru/guides/vad/android) | Определение голосовой активности | [2 МБ](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 МБ | Любой |
| [Sortformer, 4 диктора](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | Потоковая диаризация дикторов (опционально) | [475 МБ](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | ещё не измерено | Любой |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | Эмбеддинги дикторов (опционально) | [51 МБ](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | ещё не измерено | Любой |
| [DeepFilterNet3](https://soniqo.audio/ru/guides/denoise/android) | Шумоподавление | [~8 МБ](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | по умолчанию не загружается | Любой |
| [FunctionGemma 270M](https://soniqo.audio/ru/guides/function-calls) | Локальная LLM — структурированные вызовы функций / инструментов | [283 МБ](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | зависит от runtime приложения | EN-tuned |

Модели загружаются автоматически при первом запуске через `ModelManager.ensureModels()`.

`SpeechConfig()` по умолчанию использует `SttModel.PARAKEET_EOU` и `TtsModel.KOKORO_SHORT_TURN`, чтобы интеграции SDK и системный распознаватель работали по низкопамятному Android-пути. Демо-приложение выбирает `SttModel.PARAKEET`, поэтому экраны эха и диктовки используют более крупную TDT-модель для 25 европейских языков.

Обе модели Parakeet всегда определяют язык автоматически: ни одна не принимает `language` или `languageHints`, и ни одна не поддерживает китайский. Чтобы выбрать один язык, включая мандаринский китайский, используйте управляемый промптом backend Nemotron:

```kotlin
val sttModel = SttModel.NEMOTRON_MULTILINGUAL
val sttBackend = SttBackend.LITERT
val modelDir = ModelManager.ensureModels(
    context,
    sttModel = sttModel,
    sttBackend = sttBackend,
)
val config = SpeechConfig(
    modelDir = modelDir,
    sttModel = sttModel,
    sttBackend = sttBackend,
    language = "zh-CN", // или "zh-TW"
)
```

**Supertonic-3** — это опциональный многоязычный TTS повышенного качества: выберите его через `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (требуется бэкенд LiteRT). Хост выполняет его четыре неавторегрессионных flow-matching-графа на устройстве на частоте 44,1 кГц; фронтенд работает G2P-free (NFKD + индекс Unicode — без фонемизатора), поэтому все 31 язык проходят через один путь.

Длинные предложения: в бандл также входит опциональная пара графов с латентным окном L=128 (`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 МБ). Скачайте её через `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)` (или тот же флаг у `ModelManager.ensureTtsModels`), и speech-core синтезирует предложение длиннее базового окна в 4,5 с за один проход, не разбивая его; пара загружается при первом использовании (+~360 МБ RSS). По умолчанию выключено.

Kokoro и Supertonic принимают пресет голоса на каждый вызов прямого синтеза — `pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`; Kokoro `af_heart`, `ff_siwis`, …) — тот же аргумент есть у `synthesizeStreaming` и `SpeechSynthesizer`. Пресет действует только для этого вызова; опустите его (или передайте `""`), чтобы сохранить голос движка по умолчанию, а неизвестный id вызывает исключение.

## Попробовать демо

Скачайте [подписанный APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) и установите на любое arm64-устройство Android (8+). Стандартный низкопамятный набор моделей (~500 МБ) загружается автоматически при первом запуске.

## Добавить зависимость

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.24")
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

### Определение конца реплики

VAD распознаёт тишину, но не может понять, завершает ли пауза мысль говорящего.
Smart Turn v3.2 — необязательный аудиоклассификатор, который анализирует
последние восемь секунд текущей реплики и оставляет незавершённую реплику
открытой.

```kotlin
val modelDir = ModelManager.ensureModels(context, enableSmartTurn = true)

val pipeline = SpeechPipeline(
    SpeechConfig(
        modelDir = modelDir,
        enableSmartTurn = true,
        turnCompletionThreshold = 0.5f,
        turnCompletionMaxSilenceSec = 2.0f,
    )
)
```

При включении загружается закреплённый INT8-граф размером 11,1 МБ. Инференс
выполняется один раз после каждой подтверждённой VAD паузы, а не на каждом
аудиофрейме. Если пауза отклонена, возобновлённая речь остаётся в той же
реплике; ограничение максимальной тишины всё равно гарантирует завершение.
Smart Turn создан Pipecat/Daily и распространяется по BSD-2-Clause.

### Только детекция речевой активности

Приложениям, которым нужно знать лишь *когда* человек говорит — управление
push-to-talk, нарезка записи, пробуждение по голосу, — достаточно загрузить
один Silero: 2 МБ загрузки и меньше 10 МБ памяти вместо ~500 МБ набора по
умолчанию. Без STT, без TTS, без конвейера.

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // включите, чтобы получать записанный фрагмент
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 кГц float32
    }
}

// 16 кГц моно float32, кадры по 512 отсчётов
detector.pushAudio(samples)
// закрывает открытый фрагмент в конце потока
detector.flush()
```

`VadConfig` открывает ту же детекцию реплик, что конвейер использует внутри:
пороги начала и конца, минимальную длительность речи, тишину конца реплики и
пред-речевой буфер, сохраняющий первый слог. Этот путь использует
`ModelManager.ensureVadModels()` и отдельный кеш `models_vad/`, поэтому пакеты
STT/TTS не скачиваются никогда.

### Компоненты для транскрипции встреч

Приложения, которые сами ведут запись и сегментацию — диктофон для встреч, приложение для заметок, — могут загружать распознаватель, диаризатор и кодировщик дикторов по отдельности, без конвейера. У каждого своя загрузка и свой каталог кеша, и ни один не принимает продуктовых решений: транскрайбер возвращает текст, диаризатор — вероятности дикторов по кадрам, кодировщик — 192-мерный вектор голоса. Пороги, реплики, метки дикторов и сопоставление голосов остаются в приложении.

```kotlin
// Необязательно: выбранное пользователем зеркало со структурой Hugging Face.
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 кГц моно float32; текст на данный момент
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [кадры x diarizer.speakers], часто пусто
val tail = diarizer.endStream()           // когда запись закончилась

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // не меньше 2 с; FloatArray(192)
```

По умолчанию `TranscriberConfig.language` равен `"auto"` — это промпт автоматического языка Nemotron, как в speech-swift; `"en-US"`, `"pt_BR"` или просто `"fr"` фиксируют один язык. Каждая транскрипция содержит время слов в секундах от `beginStream`, взятое из кадра энкодера, на котором был выдан каждый токен. Sortformer — один поток на запись; он отвечает примерно на 30 секунд позже звука, потому что опубликованный экспорт декодирует 27,2 секунды за вызов. `ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` скачивает три набора в фоне.

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
- Голосовой оверлей: плавающая кнопка микрофона для диктовки в любом приложении
- Parakeet TDT STT для 25 европейских языков в экранах эха и диктовки
- Тестовый экран `SpeechRecognizer` — задействует системный путь голосового ввода
- UI с пузырями чата и отображением задержки STT/TTS

```bash
./gradlew :app:installDebug
```

### Голосовой оверлей (диктовка в любое приложение)

**Голосовой оверлей** размещает перетаскиваемую кнопку микрофона поверх других
приложений. По нажатию она превращается в **■ стоп** / **✕ отмена**: стоп
вводит расшифровку в текстовое поле, которое сейчас в фокусе, отмена —
отбрасывает её. Если ни одно редактируемое поле не в фокусе, текст попадает в
буфер обмена, а не теряется.

Нужны три разрешения, у каждого свой системный экран — экран настройки
показывает, каких ещё не хватает:

| Разрешение | Зачем |
| --- | --- |
| Микрофон | захват звука |
| Поверх других приложений | рисовать кнопку вне приложения |
| Специальные возможности | вводить текст в поле другого приложения |

Окно оверлея намеренно не принимает фокус, поэтому целевое поле сохраняет фокус
ввода при нажатии кнопок. Текст вставляется по позиции курсора через
`ACTION_SET_TEXT`. В поля, реальное содержимое которых прочитать нельзя (часть
приложений сообщает свой placeholder как собственный текст поля), запись идёт
вставкой — она заменяет то, что было в буфере обмена; сразу после этого
надиктованный текст из него удаляется.

> Устанавливаете из APK, а не из Play Store? Android блокирует переключатель
> специальных возможностей, пока вы не разрешите его в
> Настройки → Приложения → Speech → ⋮ → **Разрешить ограниченные настройки**.

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
