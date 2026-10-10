# Speech Android

📖 언어: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai)와 [speech-core](https://github.com/soniqo/speech-core) 기반의 Android용 온디바이스 음성 SDK.

저메모리 스트리밍 음성 인식과 선택 가능한 대형 TDT 모델(둘 다 유럽 25개 언어 지원), 텍스트 음성 변환, 음성 활동 감지, 노이즈 캔슬링 — 모두 로컬에서 실행됩니다. 클라우드 API도, 디바이스 외부로 전송되는 데이터도 없습니다.

**[📚 Android 문서](https://soniqo.audio/ko/getting-started/android)**

**[데모 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[모델](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 버전) · **[speech-core](https://github.com/soniqo/speech-core)**(파이프라인 엔진 + Linux/임베디드 빌드)

## 데모

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="완전 오프라인 음성 에이전트를 Android의 1.2 GB에 담았습니다 — YouTube에서 데모 보기">
  </a>
</p>
<p align="center"><em><a href="control-demo/">control-demo</a>의 전체 명령 루프 — Silero VAD → Parakeet STT → FunctionGemma → 기기 동작 → Pocket TTS 응답 — 완전 오프라인, RAM 1.2 GB</em></p>

## 범위

이 저장소는 **Android 패키징**입니다: Kotlin SDK, JNI 브리지, 데모 앱. C++ 엔진과 ONNX 모델 래퍼(Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3)는 [speech-core](https://github.com/soniqo/speech-core)에 있으며 git 서브모듈을 통해 가져옵니다. Linux / 자동차(Yocto, Qualcomm SA8295P/SA8255P)는 [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)에 있습니다.

## 모델

| 모델 | 작업 | 다운로드 | 피크 메모리 | 언어 |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/ko/guides/dictate) | 스트리밍 STT + EOU(기본) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/ko/guides/parakeet/android) | 광범위 STT(선택) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 유럽 25개 언어 |
| [Nemotron-3.5 다국어](https://soniqo.audio/ko/guides/nemotron) | 프롬프트 조건부 스트리밍 STT(선택) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | 미측정 | 100개 이상(zh 포함) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | 오프라인 STT + 번역 (선택) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/ko/guides/kokoro/android) | 텍스트 음성 변환(기본) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8(en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | 스트리밍 음성 합성(선택, 고정 Alba 음성) | ~126 MB | 미측정 | 영어 |
| [Supertonic-3](https://soniqo.audio/ko/guides/supertonic) | 텍스트 음성 변환(LiteRT, flow-matching, G2P-free, 44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/ko/guides/vad/android) | 음성 활동 감지 | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | 모든 언어 |
| [Sortformer 4화자](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | 스트리밍 화자 분리(선택) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | 미측정 | 모든 언어 |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 화자 임베딩(선택) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 미측정 | 모든 언어 |
| [DeepFilterNet3](https://soniqo.audio/ko/guides/denoise/android) | 노이즈 캔슬링 | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | 기본으로 로드하지 않음 | 모든 언어 |
| [FunctionGemma 270M](https://soniqo.audio/ko/guides/function-calls) | 온디바이스 LLM — 구조화 함수 / 도구 호출 | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | 앱 런타임에 따라 다름 | EN 튜닝 |

모델은 `ModelManager.ensureModels()`를 통해 첫 실행 시 자동으로 다운로드됩니다.

`SpeechConfig()`는 `SttModel.PARAKEET_EOU`와 `TtsModel.KOKORO_SHORT_TURN`를 기본값으로 사용해 SDK 통합과 시스템 인식 서비스를 저메모리 Android 경로에서 실행합니다. 데모 앱은 `SttModel.PARAKEET`를 선택해 에코와 받아쓰기 화면에서 유럽 25개 언어를 지원하는 더 큰 TDT 모델을 사용합니다.

두 Parakeet 모델은 항상 언어를 자동 감지합니다. `language`나 `languageHints`를 받지 않으며 중국어도 지원하지 않습니다. 중국어를 포함한 하나의 언어를 지정하려면 프롬프트 조건부 Nemotron 백엔드를 사용하세요:

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
    language = "zh-CN", // 또는 "zh-TW"
)
```

**Supertonic-3**는 옵트인 방식의 더 높은 품질의 다국어 TTS입니다 — `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)`로 선택하세요(LiteRT 백엔드가 필요합니다). 호스트는 4개의 비자기회귀(non-autoregressive) flow-matching 그래프를 44.1 kHz로 온디바이스에서 실행합니다. 프런트엔드는 G2P-free(NFKD + 유니코드 인덱스 — 음소 변환기 없음)이므로 31개 언어 모두 하나의 경로를 통과합니다.

긴 문장: 번들에는 L=128 잠재 윈도우 그래프 쌍(`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 MB)도 선택 사항으로 포함되어 있습니다. `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)`(또는 `ModelManager.ensureTtsModels`의 같은 플래그)로 다운로드하면 speech-core가 4.5초 기본 윈도우보다 긴 문장을 분할하지 않고 한 번에 합성합니다. 이 쌍은 처음 사용할 때 로드됩니다(+~360 MB RSS). 기본값은 꺼짐입니다.

Kokoro와 Supertonic 모두 직접 합성 시 호출 단위로 음성 프리셋을 지정할 수 있습니다 — `pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`, Kokoro `af_heart`, `ff_siwis` 등). 같은 인자가 `synthesizeStreaming`과 `SpeechSynthesizer`에도 있습니다. 프리셋은 해당 호출에만 적용되며, 생략하거나 `""`를 전달하면 엔진 기본 음성이 유지되고, 알 수 없는 id는 예외를 던집니다.

## 데모 사용해보기

[서명된 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)를 다운로드하여 arm64 Android 기기(8 이상)에 설치하세요. 기본 저메모리 모델 번들(~500 MB)은 첫 실행 시 자동으로 다운로드됩니다.

## 의존성 추가

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.24")
}
```

## Kotlin 사용법

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

// 마이크에서 16kHz 모노 float32 PCM 입력
pipeline.pushAudio(samples)
```

### 턴 종료 감지

VAD는 침묵을 감지하지만, 그 멈춤이 화자의 생각이 끝났다는 의미인지는 판단할 수
없습니다. Smart Turn v3.2는 현재 턴의 마지막 8초를 살펴보고 미완성 턴을 열린
상태로 유지하는 선택적 오디오 기반 분류기입니다.

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

옵트인 다운로드는 고정된 리비전의 11.1 MB INT8 그래프를 추가합니다. 추론은 모든
오디오 프레임이 아니라 VAD가 확인한 각 멈춤 뒤에 한 번 실행됩니다. 멈춤이 거부된
뒤 말이 재개되면 같은 턴이 이어지며, 최대 침묵 제한이 최종 종료를 보장합니다.
Smart Turn은 Pipecat/Daily가 만들었으며 BSD-2-Clause로 배포됩니다.

### 음성 활동 감지 전용

누군가 말하고 있는 *시점*만 알면 되는 앱 — 푸시투토크 게이팅, 녹음 분할, 음성
웨이크업 — 은 Silero만 로드할 수 있습니다. 기본 모델 세트 약 500 MB 대신 다운로드
2 MB, 메모리 10 MB 미만입니다. STT도 TTS도 파이프라인도 없습니다.

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // 캡처한 구간을 받으려면 켠다
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz 모노 float32, 512 샘플 프레임
detector.pushAudio(samples)
// 스트림이 끝날 때 열린 구간을 닫는다
detector.flush()
```

`VadConfig`는 파이프라인이 내부에서 쓰는 턴 감지를 그대로 노출합니다. 시작/종료
임계값, 최소 발화 길이, 발화 종료 무음 길이, 그리고 첫 음절을 지켜 주는 프리스피치
버퍼입니다. 이 경로는 `ModelManager.ensureVadModels()`와 별도의 `models_vad/`
캐시를 사용하므로 STT/TTS 번들을 내려받지 않습니다.

### 회의 전사 구성 요소

녹음과 구간 분할을 직접 처리하는 앱(회의 녹음기, 메모 앱 등)은 파이프라인 없이 인식기, 화자 분리 모델, 화자 인코더를 따로 불러올 수 있습니다. 각각 고유한 다운로드와 캐시 디렉터리를 쓰며, 어느 것도 제품 결정을 대신하지 않습니다. 전사기는 텍스트를, 화자 분리 모델은 프레임별 화자 확률을, 인코더는 192차원 음성 벡터를 반환합니다. 임곗값, 턴, 화자 레이블, 음성 매칭은 앱이 결정합니다.

```kotlin
// 선택: 사용자가 고른, Hugging Face 구조를 따르는 미러.
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz 모노 float32; 지금까지의 텍스트
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [프레임 수 x diarizer.speakers], 대개 비어 있음
val tail = diarizer.endStream()           // 녹음이 끝날 때

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // 최소 2초; FloatArray(192)
```

`TranscriberConfig.language`의 기본값은 `"auto"`로, speech-swift와 마찬가지로 Nemotron의 자동 언어 프롬프트를 사용합니다. `"en-US"`, `"pt_BR"`, 또는 `"fr"`처럼 언어만 지정하면 한 언어로 고정됩니다. 전사 결과에는 각 토큰이 출력된 인코더 프레임을 기준으로 한 단어별 타이밍(`beginStream`부터의 초)이 포함됩니다. Sortformer는 녹음당 하나의 스트림이며, 공개된 내보내기가 호출당 27.2초를 디코딩하므로 결과가 오디오보다 약 30초 늦게 나옵니다. `ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)`로 세 세트를 백그라운드에서 받을 수 있습니다.

## 소스에서 빌드

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38개 e2e 테스트
```

`./setup.sh`는 speech-core 서브모듈을 초기화하고 ONNX Runtime을
`./ort/`로 다운로드합니다.

## 데모 앱

[`app/`](app/) 모듈은 최소한의 음성 비서 데모로 다음을 포함합니다:

- 실시간 VAD 파형 시각화
- 에코 모드: 음성을 전사하고 다시 합성(LLM 없음)
- 받아쓰기 모드: 스트리밍 부분 결과
- 음성 오버레이: 어떤 앱에나 받아쓸 수 있는 플로팅 마이크 버튼
- 에코와 받아쓰기 화면에서 유럽 25개 언어를 지원하는 Parakeet TDT STT 사용
- `SpeechRecognizer` 테스트 화면 — 시스템 전체 음성 입력 경로 실행
- STT/TTS 지연 시간 표시가 있는 채팅 버블 UI

```bash
./gradlew :app:installDebug
```

### 음성 오버레이 (어떤 앱에나 받아쓰기)

**음성 오버레이**는 다른 앱 위에 드래그 가능한 마이크 버튼을 띄웁니다. 탭하면
**■ 중지** / **✕ 취소** 로 바뀝니다. 중지는 현재 포커스된 텍스트 필드에 전사
결과를 입력하고, 취소는 폐기합니다. 편집 가능한 필드가 포커스되어 있지 않으면
텍스트는 사라지지 않고 클립보드로 복사됩니다.

세 가지 권한이 필요하며 각각 별도의 시스템 화면에서 부여합니다. 설정 화면에서
아직 없는 권한을 확인할 수 있습니다:

| 권한 | 용도 |
| --- | --- |
| 마이크 | 오디오 캡처 |
| 다른 앱 위에 표시 | 앱 바깥에 버튼 그리기 |
| 접근성 서비스 | 다른 앱의 텍스트 필드에 입력 |

오버레이 창은 의도적으로 포커스를 받지 않도록 설정되어 있어, 버튼을 탭해도
대상 필드가 입력 포커스를 유지합니다. 텍스트는 `ACTION_SET_TEXT` 로 커서
위치에 삽입됩니다. 실제 내용을 읽을 수 없는 필드(일부 앱은 플레이스홀더를
필드 자체의 텍스트로 보고합니다)에는 대신 붙여넣기로 기록하므로 클립보드에
있던 내용이 대체되며, 붙여넣기 직후 받아쓴 텍스트는 지워집니다.

> Play 스토어가 아닌 APK로 설치했나요? Android는 설정 → 앱 → Speech → ⋮ →
> **제한된 설정 허용** 을 거치기 전까지 접근성 토글을 차단합니다.

### 전체 파이프라인 제어 데모

별도의 [`control-demo/`](control-demo/) 앱은 Silero VAD →
Parakeet-EOU STT → FunctionGemma 270M 도구 호출 → Android 기기 동작 →
Pocket TTS로 이어지는 전체 에이전트를 로컬에서 실행합니다. 단계별 지연 시간을
표시하고 이 체크아웃의 `:sdk`에 직접 연결하므로 로컬 음성 최적화를 사용합니다.

최신 릴리스에서 [서명된 Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)를 다운로드하거나 소스에서 개발 빌드를 설치하세요.

```bash
./gradlew :control-demo:installDebug
```

## 시스템 음성 입력(`RecognitionService`)

SDK는 Android 프레임워크 `SpeechRecognizer` API에 연결되는 바로 사용 가능한 `audio.soniqo.speech.service.SpeechRecognitionService`를 제공합니다 — 작성할 코드가 없습니다. 앱이 기본 음성 인식기로 선택되면, `SpeechRecognizer.createSpeechRecognizer(context)`(`ComponentName` 없이)를 호출하는 모든 타사 앱이 파이프라인을 통해 완전히 온디바이스 STT를 받을 수 있습니다.

**1. `AndroidManifest.xml`에서 `RECORD_AUDIO`와 서비스를 선언합니다:**

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

**2. `app/src/main/res/xml/recognition_service.xml`을 추가합니다:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(선택적으로 `android:settingsActivity="..."`를 추가하면 시스템 음성 입력 선택기에 톱니바퀴 아이콘이 노출됩니다.)

**3. 서비스를 시스템 기본값으로 설정합니다**(스톡 Android에서는 설정 → 시스템 → 언어 및 입력 → 음성 입력 선택기 또는 adb를 통해):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. 검증**: 데모 앱의 *Recognizer test* 화면을 실행하면 `SpeechRecognizer.createSpeechRecognizer(ctx)`(컴포넌트 없이)를 호출하고 모든 프레임워크 콜백을 기록합니다 — logcat 없이 binder 왕복을 확인하는 데 유용합니다.

서비스는 `onCheckRecognitionSupport`(API 33+)를 구현하여 Parakeet-EOU가 지원하는 25개 BCP-47 기본 언어와, 지원되는 기본 언어에 매핑되는 경우 요청된 정확한 지역 태그를 반환합니다. 모델이 존재하면 `installedOnDeviceLanguage`, 다운로드 전에는 `supportedOnDeviceLanguage`로 표시됩니다. 서비스는 호출 앱의 오디오 포커스를 가져가지 않습니다.

**주의:** Gboard, 삼성 키보드, Google Assistant는 자체 인식 엔진을 번들로 제공하며 시스템 기본값을 건너뜁니다. 프레임워크 `SpeechRecognizer` API를 명시적으로 호출하거나 그 위에 자체 UI를 구축하는 앱만이 서비스를 통과합니다.

## 시스템 텍스트 음성 변환(`TextToSpeechService`)

데모 앱은 `audio.soniqo.speech.service.SpeechTextToSpeechService`도 노출하므로 Android 설정 → 시스템 → 언어 및 입력 → 텍스트 음성 변환 출력에서 이 앱을 선택할 수 있습니다. 이 경로는 `ModelManager.ensureTtsModels()`와 별도의 `models_tts/` 캐시를 사용하므로, 프레임워크 TTS는 전체 VAD/STT/enhancer 파이프라인 번들이 아니라 Kokoro 자산만 다운로드합니다.

다른 앱에서 엔진을 노출하려면 서비스를 선언합니다:

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

`app/src/main/res/xml/tts_engine.xml`을 추가합니다:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## 성능

Galaxy S23 Ultra(SM-S918B)에서 CPU만 사용해 측정했습니다. RTF는 경과 시간÷생성 오디오 길이이며, 낮을수록 빠르고 <1.0이면 실시간보다 빠릅니다.

| 모델 | 작업 | RTF | 지연 시간 | 피크 메모리 |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | 스트리밍 STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M 전체 그래프(공개 버전, CPU 2 스레드) | TTS | 1.81 | 문장 단위 | ~604 MB |
| Kokoro 82M 짧은 턴(3.0초 그래프, 기본값) | TTS | 0.75–0.88 | 제한된 응답, 안전한 재시도 | ~527 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1초 TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | 32ms 청크당 <1ms | <10 MB |

## 파이프라인

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

바지인(barge-in) 지원: TTS 재생 중 말하면 중단되고 새 전사가 시작됩니다.

## 아키텍처

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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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

각 모델 클래스는 해당하는 speech-core 인터페이스(`VADInterface`,
`STTInterface`, `TTSInterface`, `EnhancerInterface`)를 직접 구현합니다 —
JNI 브리지가 이들을 인스턴스화하여 `VoicePipeline`에 참조를 전달합니다.
C-vtable 어댑터 보일러플레이트가 없습니다.

## 하드웨어 가속

| 칩셋 | 가속 |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU 폴백 | XNNPACK |

자동차용 Qualcomm SA8295P / SA8255P와 QNN(Hexagon DSP)은
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)를 참조하세요.

## 관련 프로젝트

| 저장소 | 범위 |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | 크로스 플랫폼 C++ 파이프라인 엔진 + ONNX 모델 래퍼 + Linux/임베디드 예제 |
| **speech-android** | Android 래퍼 — speech-core 위에 Kotlin SDK + JNI 브리지 |

## 라이선스

Apache 2.0
