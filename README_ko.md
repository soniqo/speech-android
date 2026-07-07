# Speech Android

📖 언어: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai)와 [speech-core](https://github.com/soniqo/speech-core) 기반의 Android용 온디바이스 음성 SDK.

저메모리 스트리밍 음성 인식(기본 25개 언어, 114개 언어 TDT는 선택), 텍스트 음성 변환, 음성 활동 감지, 노이즈 캔슬링 — 모두 로컬에서 실행됩니다. 클라우드 API도, 디바이스 외부로 전송되는 데이터도 없습니다.

**[데모 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[모델](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 버전) · **[speech-core](https://github.com/soniqo/speech-core)**(파이프라인 엔진 + Linux/임베디드 빌드)

## 범위

이 저장소는 **Android 패키징**입니다: Kotlin SDK, JNI 브리지, 데모 앱. C++ 엔진과 ONNX 모델 래퍼(Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3)는 [speech-core](https://github.com/soniqo/speech-core)에 있으며 git 서브모듈을 통해 가져옵니다. Linux / 자동차(Yocto, Qualcomm SA8295P/SA8255P)는 [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)에 있습니다.

## 모델

| 모델 | 작업 | 다운로드 | 피크 메모리 | 언어 |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 스트리밍 STT + EOU(기본) | 153 MB | 232 MB | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | 광범위 STT(선택) | 891 MB | ~1.1-1.3 GB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 텍스트 음성 변환(기본) | 330 MB | 640 MB | 8(en, fr, es, it, pt, hi, ja, zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 텍스트 음성 변환(LiteRT, flow-matching, G2P-free, 44.1 kHz) | ~380 MB | 832 MB | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | 음성 활동 감지 | 2 MB | <10 MB | 모든 언어 |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | 노이즈 캔슬링 | ~8 MB | 기본으로 로드하지 않음 | 모든 언어 |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | 온디바이스 LLM — 구조화 함수 / 도구 호출 | 283 MB | 앱 런타임에 따라 다름 | EN 튜닝 |

모델은 `ModelManager.ensureModels()`를 통해 첫 실행 시 자동으로 다운로드됩니다.

`SpeechConfig()`는 `SttModel.PARAKEET_EOU`와 `TtsModel.KOKORO`를 기본값으로 사용해 데모와 시스템 인식 서비스를 저메모리 Android 경로에서 실행합니다. 더 큰 114개 언어 TDT 모델이 필요할 때만 `SpeechConfig(sttModel = SttModel.PARAKEET)`를 사용하세요.

**Supertonic-3**는 옵트인 방식의 더 높은 품질의 다국어 TTS입니다 — `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)`로 선택하세요(LiteRT 백엔드가 필요합니다). 호스트는 4개의 비자기회귀(non-autoregressive) flow-matching 그래프를 44.1 kHz로 온디바이스에서 실행합니다. 프런트엔드는 G2P-free(NFKD + 유니코드 인덱스 — 음소 변환기 없음)이므로 31개 언어 모두 하나의 경로를 통과합니다.

## 데모 사용해보기

[서명된 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)를 다운로드하여 arm64 Android 기기(8 이상)에 설치하세요. 기본 저메모리 모델 번들(~500 MB)은 첫 실행 시 자동으로 다운로드됩니다.

## 의존성 추가

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin 사용법

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

// 마이크에서 16kHz 모노 float32 PCM 입력
pipeline.pushAudio(samples)
```

## 소스에서 빌드

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34개 e2e 테스트
```

`./setup.sh`는 speech-core 서브모듈을 초기화하고 ONNX Runtime을
`./ort/`로 다운로드합니다.

## 데모 앱

[`app/`](app/) 모듈은 최소한의 음성 비서 데모로 다음을 포함합니다:

- 실시간 VAD 파형 시각화
- 에코 모드: 음성을 전사하고 다시 합성(LLM 없음)
- 받아쓰기 모드: 스트리밍 부분 결과
- `SpeechRecognizer` 테스트 화면 — 시스템 전체 음성 입력 경로 실행
- STT/TTS 지연 시간 표시가 있는 채팅 버블 UI

```bash
./gradlew :app:installDebug
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

Android 에뮬레이터(arm64-v8a, NNAPI 없음)에서 측정. 실제 하드웨어는 훨씬 빠릅니다.

Galaxy S23 Android에서 측정했습니다. 별도 표기가 없으면 CPU 기준입니다. RTF는 낮을수록 빠릅니다.

| 모델 | 작업 | RTF | 지연 시간 | 피크 메모리 |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | 스트리밍 STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M ONNX FP32 | TTS | 0.53 | 문장 단위 | 640 MB |
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
