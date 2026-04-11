# Speech Android

📖 언어: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai)와 [speech-core](https://github.com/soniqo/speech-core) 기반의 Android 및 임베디드 Linux용 온디바이스 음성 SDK.

음성 인식(114개 언어), 텍스트 음성 변환(8개 언어), 음성 활동 감지, 노이즈 캔슬링 — 모두 로컬에서 실행됩니다. 클라우드 API도, 디바이스 외부로 전송되는 데이터도 없습니다.

**[데모 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[모델](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 버전) · **[speech-core](https://github.com/soniqo/speech-core)**(파이프라인 엔진)

## 플랫폼

| 플랫폼 | API | 가속 | 디렉토리 |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI(Snapdragon, Exynos, Tensor) | `sdk/` |
| 임베디드 Linux | C (`speech.h`) | QNN(Hexagon DSP) | `linux/` |

## 모델

| 모델 | 작업 | INT8 크기 | 언어 |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | 음성 인식 | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | 텍스트 음성 변환 | 330 MB | 8(en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | 음성 활동 감지 | 2 MB | 모든 언어 |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | 노이즈 캔슬링 | ~8 MB | 모든 언어 |

모델은 첫 실행 시 자동 다운로드(Android)되거나 수동으로 배치(Linux)됩니다.

## Android

### 데모 사용해보기

[서명된 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)를 다운로드하여 arm64 Android 기기(8 이상)에 설치하세요. 모델(~1.2 GB)은 첫 실행 시 자동으로 다운로드됩니다.

### 의존성 추가

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Kotlin 사용법

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

### 소스에서 빌드

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34개 e2e 테스트
```

### 데모 앱

[`app/`](app/) 모듈은 최소한의 음성 비서 데모로 다음을 포함합니다:

- 실시간 VAD 파형 시각화
- 에코 모드: 음성을 전사하고 다시 합성(LLM 없음)
- STT/TTS 지연 시간 표시가 있는 채팅 버블 UI

```bash
./gradlew :app:installDebug
```

## 성능

Android 에뮬레이터(arm64-v8a, NNAPI 없음)에서 측정. 실제 하드웨어는 훨씬 빠릅니다.

| 모델 | 작업 | 오디오 | 추론 | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5초 | 175ms | 0.12 |
| Kokoro 82M | TTS | 1.9초 출력 | 1,075ms | 0.58 |
| Silero VAD v5 | VAD | 32ms 청크 | <1ms | <0.01 |

## 임베디드 Linux

자동차 및 임베디드 플랫폼을 위한 최소한의 C API. 전체 문서는 [`linux/README.md`](linux/README.md)를 참조하세요.

### C API 사용법

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon DSP 가속

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### 빌드

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### 테스트

```bash
linux/tests/download_models.sh              # ONNX 모델 다운로드
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12개 테스트
```

### Yocto용 크로스 컴파일

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

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

## 하드웨어 가속

| 플랫폼 | 칩셋 | 가속 |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| 자동차 | SA8295P / SA8255P | QNN → Hexagon DSP |
| 모두 | CPU 폴백 | XNNPACK |

## 관련 프로젝트

| 저장소 | 플랫폼 |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | 크로스 플랫폼 C++ 파이프라인 엔진 |
| **speech-android** | Android + 임베디드 Linux — ONNX Runtime |

## 라이선스

Apache 2.0
