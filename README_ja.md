# Speech Android

📖 言語: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai) と [speech-core](https://github.com/soniqo/speech-core) を活用した、Android 向けオンデバイス音声 SDK。

低メモリのストリーミング音声認識(既定 25 言語、114 言語 TDT は任意)、テキスト読み上げ、音声活動検出、ノイズキャンセリング — すべてローカルで動作。クラウド API 不要、データはデバイスから外に出ません。

**[デモ APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[モデル](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 版)· **[speech-core](https://github.com/soniqo/speech-core)**(パイプラインエンジン + Linux/組み込みビルド)

## スコープ

このリポジトリは **Android パッケージング** を担当します:Kotlin SDK、JNI ブリッジ、デモアプリ。C++ エンジンおよび ONNX モデルラッパー(Silero VAD、Parakeet STT、Kokoro TTS、DeepFilterNet3)は [speech-core](https://github.com/soniqo/speech-core) に存在し、git サブモジュールとして取り込まれます。Linux / 自動車向け(Yocto、Qualcomm SA8295P/SA8255P)は [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) に存在します。

## モデル

| モデル | タスク | ダウンロード | ピークメモリ | 言語 |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | ストリーミング STT + EOU(既定) | 153 MB | 232 MB | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | 広範囲 STT(任意) | 891 MB | ~1.1-1.3 GB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | テキスト読み上げ(既定) | 330 MB | 640 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | テキスト読み上げ(LiteRT、flow-matching、G2P-free、44.1 kHz) | ~380 MB | 832 MB | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | 音声活動検出 | 2 MB | <10 MB | 任意 |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | ノイズキャンセリング | ~8 MB | 既定では未ロード | 任意 |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | オンデバイス LLM — 構造化関数 / ツール呼び出し | 283 MB | アプリのランタイム次第 | EN チューニング |

モデルは初回起動時に `ModelManager.ensureModels()` 経由で自動ダウンロードされます。

`SpeechConfig()` は `SttModel.PARAKEET_EOU` と `TtsModel.KOKORO` を既定にして、デモとシステム認識サービスを低メモリ Android パスで動かします。より大きい 114 言語 TDT モデルが必要な場合のみ `SpeechConfig(sttModel = SttModel.PARAKEET)` を使います。

**Supertonic-3** はオプトインの高品質な多言語 TTS です — `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` で選択します(LiteRT バックエンドが必要)。ホストはその 4 つの非自己回帰 flow-matching グラフを 44.1 kHz でオンデバイス実行します。フロントエンドは G2P-free(NFKD + Unicode インデックス — phonemizer なし)なので、31 言語すべてが単一のパスを通ります。

## デモを試す

[署名済み APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) をダウンロードし、任意の arm64 Android デバイス(8 以降)にインストールします。既定の低メモリモデルバンドル(~500 MB)は初回起動時に自動ダウンロードされます。

## 依存関係を追加

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin の使い方

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

// マイクから 16kHz モノラル float32 PCM を入力
pipeline.pushAudio(samples)
```

## ソースからビルド

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 個の e2e テスト
```

`./setup.sh` は speech-core サブモジュールを初期化し、ONNX Runtime を
`./ort/` にダウンロードします。

## デモアプリ

[`app/`](app/) モジュールは最小限の音声アシスタントデモで、以下を含みます:

- リアルタイム VAD 波形の可視化
- エコーモード:音声を文字起こしして合成し直す(LLM なし)
- ディクテーションモード:ストリーミング部分結果
- `SpeechRecognizer` テスト画面 — システム全体の音声入力パスを実行
- STT/TTS のレイテンシ表示付きチャットバブル UI

```bash
./gradlew :app:installDebug
```

## システム音声入力(`RecognitionService`)

SDK には、Android フレームワークの `SpeechRecognizer` API に組み込めるすぐに使える `audio.soniqo.speech.service.SpeechRecognitionService` が含まれています — コードを書く必要はありません。アプリがデフォルトの音声認識サービスに選択されると、`SpeechRecognizer.createSpeechRecognizer(context)`(`ComponentName` なし)を呼び出す任意のサードパーティアプリが、あなたのパイプラインを通じて完全なオンデバイス STT を利用できます。

**1. `AndroidManifest.xml` で `RECORD_AUDIO` とサービスを宣言します:**

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

**2. `app/src/main/res/xml/recognition_service.xml` を追加します:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(オプションで `android:settingsActivity="..."` を追加すると、システム音声入力ピッカーに歯車アイコンが表示されます。)

**3. サービスをシステムデフォルトに設定します**(標準 Android では 設定 → システム → 言語と入力 → 音声入力ピッカー、または adb 経由):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. 確認**:デモアプリの *Recognizer test* 画面を実行します。これは `SpeechRecognizer.createSpeechRecognizer(ctx)`(コンポーネントなし)を呼び出し、すべてのフレームワークコールバックをログに記録します — logcat なしで binder のラウンドトリップを確認するのに便利です。

サービスは `onCheckRecognitionSupport`(API 33+)を実装し、Parakeet-EOU がカバーする 25 の BCP-47 基本言語に加えて、対応する基本言語に一致する場合は要求された正確な地域タグも返します。モデルが存在する場合は `installedOnDeviceLanguage`、ダウンロード前は `supportedOnDeviceLanguage` でマークされます。サービスは呼び出し元アプリからオーディオフォーカスを奪いません。

**注意:** Gboard、Samsung Keyboard、Google Assistant は独自の認識エンジンを同梱しており、システムデフォルトをスキップします。あなたのサービスを通過するのは、フレームワーク `SpeechRecognizer` API を明示的に呼び出すアプリ(またはその上に独自 UI を構築するアプリ)です。

## システム音声合成(`TextToSpeechService`)

デモアプリは `audio.soniqo.speech.service.SpeechTextToSpeechService` も公開するため、Android の 設定 → システム → 言語と入力 → テキスト読み上げ出力 でこのアプリを選択できます。この経路は `ModelManager.ensureTtsModels()` と別個の `models_tts/` キャッシュを使用するため、フレームワーク TTS は VAD/STT/enhancer を含む完全なパイプライン一式ではなく Kokoro アセットだけをダウンロードします。

別のアプリでエンジンを公開するには、サービスを宣言します:

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

`app/src/main/res/xml/tts_engine.xml` を追加します:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## パフォーマンス

Android エミュレータ(arm64-v8a、NNAPI なし)で測定。実機ははるかに高速です。

Galaxy S23 Android で測定。特記がない限り CPU。RTF は低いほど高速です。

| モデル | タスク | RTF | レイテンシ | ピークメモリ |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | ストリーミング STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M ONNX FP32 | TTS | 0.53 | 文単位 | 640 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1 秒 TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | 32 ミリ秒チャンクあたり <1 ミリ秒 | <10 MB |

## パイプライン

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

割り込み対応:TTS 再生中の発話は再生を中断して新しい文字起こしを開始します。

## アーキテクチャ

```text
┌──────────────────────────────────────────────┐
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 行)               │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (git サブモジュール) │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (オーケストレーション:    │    │
│  │   パイプライン · ターン · 割り込み)     │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

各モデルクラスは対応する speech-core インターフェース
(`VADInterface`、`STTInterface`、`TTSInterface`、`EnhancerInterface`)を
直接実装します — JNI ブリッジがそれらをインスタンス化し、参照を
`VoicePipeline` に渡します。C vtable アダプタの定型コードは不要です。

## ハードウェアアクセラレーション

| チップセット | アクセラレーション |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU フォールバック | XNNPACK |

自動車向け Qualcomm SA8295P / SA8255P と QNN(Hexagon DSP)については、
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) を参照してください。

## 関連プロジェクト

| リポジトリ | スコープ |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS、iOS)— MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | クロスプラットフォーム C++ パイプラインエンジン + ONNX モデルラッパー + Linux/組み込み例 |
| **speech-android** | Android ラッパー — speech-core 上の Kotlin SDK + JNI ブリッジ |

## ライセンス

Apache 2.0
