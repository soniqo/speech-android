# Speech Android

📖 言語: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai) と [speech-core](https://github.com/soniqo/speech-core) を活用した、Android 向けオンデバイス音声 SDK。

低メモリのストリーミング音声認識と、任意で選べるより大きな TDT モデル(どちらもヨーロッパの 25 言語に対応)、テキスト読み上げ、音声活動検出、ノイズキャンセリング — すべてローカルで動作。クラウド API 不要、データはデバイスから外に出ません。

**[📚 Android ドキュメント](https://soniqo.audio/ja/getting-started/android)**

**[デモ APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[モデル](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 版)· **[speech-core](https://github.com/soniqo/speech-core)**(パイプラインエンジン + Linux/組み込みビルド)

## デモ

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="完全オフラインの音声エージェントを Android の 1.2 GB に収めました — YouTube でデモを見る">
  </a>
</p>
<p align="center"><em><a href="control-demo/">control-demo</a> の完全なコマンドループ — Silero VAD → Parakeet STT → FunctionGemma → デバイス操作 → Pocket TTS 応答 — 完全オフライン、RAM 1.2 GB</em></p>

## スコープ

このリポジトリは **Android パッケージング** を担当します:Kotlin SDK、JNI ブリッジ、デモアプリ。C++ エンジンおよび ONNX モデルラッパー(Silero VAD、Parakeet STT、Kokoro/Pocket TTS、DeepFilterNet3)は [speech-core](https://github.com/soniqo/speech-core) に存在し、git サブモジュールとして取り込まれます。Linux / 自動車向け(Yocto、Qualcomm SA8295P/SA8255P)は [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) に存在します。

## モデル

| モデル | タスク | ダウンロード | ピークメモリ | 言語 |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/ja/guides/dictate) | ストリーミング STT + EOU(既定) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/ja/guides/parakeet/android) | 広範囲 STT(任意) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | ヨーロッパの 25 言語 |
| [Nemotron-3.5 多言語](https://soniqo.audio/ja/guides/nemotron) | プロンプト条件付きストリーミング STT(任意) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | 未計測 | 100 以上(zh を含む) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | オフライン STT + 翻訳（任意） | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/ja/guides/kokoro/android) | テキスト読み上げ(既定) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | ストリーミング音声合成(任意、固定 Alba 音声) | ~126 MB | 未計測 | 英語 |
| [Supertonic-3](https://soniqo.audio/ja/guides/supertonic) | テキスト読み上げ(LiteRT、flow-matching、G2P-free、44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/ja/guides/vad/android) | 音声活動検出 | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | 任意 |
| [Sortformer 4 話者](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | ストリーミング話者分離(任意) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | 未計測 | 任意 |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 話者埋め込み(任意) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 未計測 | 任意 |
| [DeepFilterNet3](https://soniqo.audio/ja/guides/denoise/android) | ストリーミングノイズ除去（既定） | [8.2 MiB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | 未計測 | 任意 |
| [FunctionGemma 270M](https://soniqo.audio/ja/guides/function-calls) | オンデバイス LLM — 構造化関数 / ツール呼び出し | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | アプリのランタイム次第 | EN チューニング |

モデルは初回起動時に `ModelManager.ensureModels()` 経由で自動ダウンロードされます。

DeepFilterNet3 は `SpeechConfig.enableEnhancer` により既定で有効です。音声パケット間でニューラルネットワークと DSP の状態を保持し、CPU 上で 16↔48 kHz のリサンプリングとノイズ除去を行ってから VAD/STT に渡します。モデルの遅延 40 ms にリサンプリングの遅延が加わります。無効にするには、`SpeechConfig` と `ModelManager.ensureModels()`（または `ModelDownloadWorker.enqueue()`）の両方で `enableEnhancer = false` を設定してください。有効な既存キャッシュには、固定リビジョンの `deepfilter.onnx` 重み（8.2 MiB）のみ追加でダウンロードされ、従来の補助ファイルは不要です。

`SpeechConfig()` は `SttModel.PARAKEET_EOU` と `TtsModel.KOKORO_SHORT_TURN` を既定にして、SDK 組み込みとシステム認識サービスを低メモリ Android パスで動かします。デモアプリは `SttModel.PARAKEET` を選択し、エコー画面とディクテーション画面でヨーロッパの 25 言語に対応する、より大きな TDT モデルを使います。

どちらの Parakeet モデルも常に言語を自動検出します。`language` と `languageHints` は受け付けず、中国語にも対応していません。中国語を含む単一言語を指定するには、プロンプト条件付き Nemotron バックエンドを使用します:

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
    language = "zh-CN", // または "zh-TW"
)
```

**Supertonic-3** はオプトインの高品質な多言語 TTS です — `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` で選択します(LiteRT バックエンドが必要)。ホストはその 4 つの非自己回帰 flow-matching グラフを 44.1 kHz でオンデバイス実行します。フロントエンドは G2P-free(NFKD + Unicode インデックス — phonemizer なし)なので、31 言語すべてが単一のパスを通ります。

長い文: バンドルには、L=128 の潜在ウィンドウを持つオプションのグラフペア(`vector_estimator_L128.tflite` + `vocoder_L128.tflite`、+341 MB)も含まれています。`ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)`(または `ModelManager.ensureTtsModels` の同じフラグ)でダウンロードすると、speech-core は 4.5 秒の基本ウィンドウより長い文を分割せずに一度で合成します。このペアは初回使用時に読み込まれます(+~360 MB RSS)。デフォルトではオフです。

Kokoro と Supertonic はどちらも、直接合成の呼び出しごとに音声プリセットを指定できます — `pipeline.synthesize("Hello", "en", "M1")`(Supertonic は `F1`…`F5` / `M1`…`M5`、Kokoro は `af_heart`、`ff_siwis` など)。同じ引数は `synthesizeStreaming` と `SpeechSynthesizer` にもあります。プリセットはその呼び出しにのみ適用され、省略(または `""` を指定)するとエンジンのデフォルト音声のままになります。未知の id は例外を投げます。

## デモを試す

[署名済み APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) をダウンロードし、任意の arm64 Android デバイス(8 以降)にインストールします。既定の低メモリモデルバンドル(~500 MB)は初回起動時に自動ダウンロードされます。

## 依存関係を追加

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.24")
}
```

## Kotlin の使い方

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

// マイクから 16kHz モノラル float32 PCM を入力
pipeline.pushAudio(samples)
```

### ターン終了検出

VAD は無音を検出できますが、その間が話者の発話完了を意味するかは判断できません。
Smart Turn v3.2 は、現在のターンの直近 8 秒を確認し、未完了のターンを開いたままに
するオプションの音声ベース分類器です。

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

有効にすると、固定リビジョンの 11.1 MB INT8 グラフが追加でダウンロードされます。
推論は全音声フレームではなく、VAD が確定した各ポーズの後に 1 回だけ実行されます。
ポーズが却下されて発話が再開すると同じターンが継続し、最大無音時間によって最終的な
終了も保証されます。Smart Turn は Pipecat/Daily が開発し、BSD-2-Clause で配布
されています。

### 音声区間検出のみ

誰かが話している*タイミング*だけを知りたいアプリ — プッシュトゥトークの制御、
録音の分割、音声によるウェイクアップ — は Silero 単体を読み込めます。デフォルト
のモデルセット約 500 MB に対し、ダウンロード 2 MB・メモリ 10 MB 未満です。STT も
TTS もパイプラインも読み込みません。

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // 取得した区間を受け取る場合にオンにする
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz モノラル float32、512 サンプル単位
detector.pushAudio(samples)
// ストリーム終了時に開いたままの区間を閉じる
detector.flush()
```

`VadConfig` はパイプラインが内部で使うターン検出をそのまま公開します。オンセット /
オフセットのしきい値、最短発話長、発話終了とみなす無音長、そして最初の音節を
取りこぼさないプリスピーチバッファです。このパスは `ModelManager.ensureVadModels()`
と専用の `models_vad/` キャッシュを使うため、STT/TTS のバンドルを取得することは
ありません。

### 会議文字起こしの構成要素

録音と区間分割を自前で行うアプリ(会議レコーダーやメモアプリなど)は、パイプラインなしで認識器・話者分離モデル・話者エンコーダーを個別に読み込めます。それぞれ専用のダウンロードとキャッシュディレクトリを持ち、どれもプロダクト上の判断はしません。文字起こしはテキストを、話者分離はフレームごとの話者確率を、エンコーダーは 192 次元の声のベクトルを返します。しきい値、ターン、話者ラベル、声の照合はアプリ側に残ります。

```kotlin
// 任意: ユーザーが選んだ、Hugging Face と同じ構成のミラー。
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz モノラル float32。ここまでのテキスト
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [フレーム数 x diarizer.speakers]。多くの場合は空
val tail = diarizer.endStream()           // 録音の終了時

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // 2 秒以上。FloatArray(192)
```

`TranscriberConfig.language` の既定値は `"auto"` で、speech-swift と同じく Nemotron の自動言語プロンプトを使います。`"en-US"`、`"pt_BR"`、単独の `"fr"` などを指定すると言語を固定します。文字起こしには、各トークンが出力されたエンコーダーフレームに基づく単語ごとのタイミング(`beginStream` からの秒数)が含まれます。Sortformer は録音ごとに 1 つのストリームで、公開エクスポートが 1 回の呼び出しで 27.2 秒をデコードするため、結果は音声より約 30 秒遅れます。`ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` で 3 つのセットをバックグラウンドで取得できます。

## ソースからビルド

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 個の e2e テスト
```

`./setup.sh` は speech-core サブモジュールを初期化し、ONNX Runtime を
`./ort/` にダウンロードします。

## デモアプリ

[`app/`](app/) モジュールは最小限の音声アシスタントデモで、以下を含みます:

- リアルタイム VAD 波形の可視化
- エコーモード:音声を文字起こしして合成し直す(LLM なし)
- ディクテーションモード:ストリーミング部分結果
- 音声オーバーレイ:任意のアプリに口述入力できるフローティングマイクボタン
- エコー画面とディクテーション画面でヨーロッパの 25 言語に対応する Parakeet TDT STT を使用
- `SpeechRecognizer` テスト画面 — システム全体の音声入力パスを実行
- STT/TTS のレイテンシ表示付きチャットバブル UI

```bash
./gradlew :app:installDebug
```

### 音声オーバーレイ(任意のアプリへ口述入力)

**音声オーバーレイ**は他のアプリの上にドラッグ可能なマイクボタンを表示します。
タップすると **■ 停止** / **✕ キャンセル** に変わり、停止はフォーカス中の
テキストフィールドに文字起こしを入力し、キャンセルは破棄します。編集可能な
フィールドがフォーカスされていない場合、テキストは失われずクリップボードに
コピーされます。

3 つの権限が必要で、それぞれ専用のシステム画面があります。設定画面に未付与の
ものが表示されます:

| 権限 | 用途 |
| --- | --- |
| マイク | 音声の取得 |
| 他のアプリの上に重ねて表示 | アプリ外にボタンを描画 |
| ユーザー補助サービス | 他アプリのテキストフィールドへ入力 |

オーバーレイウィンドウは意図的にフォーカスを受け取らない設定です。これにより
ボタンをタップしても対象フィールドが入力フォーカスを保持します。テキストは
`ACTION_SET_TEXT` でカーソル位置に挿入します。実際の内容を読み取れない
フィールド(プレースホルダーをフィールド自身のテキストとして報告するアプリが
あります)では代わりに貼り付けで書き込むため、クリップボードの内容が
置き換わります。貼り付け直後に口述テキストは消去されます。

> Play ストアではなく APK からインストールした場合、Android はユーザー補助の
> トグルをブロックします。設定 → アプリ → Speech → ⋮ →
> **制限された設定を許可** で解除してください。

### フルパイプライン制御デモ

独立した [`control-demo/`](control-demo/) アプリは、Silero VAD →
Parakeet-EOU STT → FunctionGemma 270M ツール呼び出し → Android
デバイス操作 → Pocket TTS というエージェント全体をローカルで実行します。
各段階のレイテンシを表示し、このチェックアウトの `:sdk` に直接リンクするため、
ローカルの音声最適化が使われます。

最新リリースから[署名済み Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)をダウンロードするか、ソースから開発ビルドをインストールします：

```bash
./gradlew :control-demo:installDebug
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

Galaxy S23 Ultra（SM-S918B）で CPU のみを使用して測定。RTF は実行時間÷生成音声時間で、低いほど速く、<1.0 はリアルタイムより高速です。

| モデル | タスク | RTF | レイテンシ | ピークメモリ |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | ストリーミング STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M フルグラフ（公開版、CPU 2 スレッド） | TTS | 1.81 | 文単位 | ~604 MB |
| Kokoro 82M 短ターン（3.0 秒グラフ、デフォルト） | TTS | 0.75–0.88 | 制限付き応答、安全な再試行 | ~527 MB |
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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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
