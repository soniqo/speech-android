# Speech Android

📖 भाषाएँ: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

Android के लिए ऑन-डिवाइस स्पीच SDK, [ONNX Runtime](https://onnxruntime.ai) और [speech-core](https://github.com/soniqo/speech-core) द्वारा संचालित।

कम-मेमोरी स्ट्रीमिंग स्पीच रिकग्निशन और वैकल्पिक बड़ा TDT मॉडल, दोनों 25 यूरोपीय भाषाओं के लिए, साथ में टेक्स्ट-टू-स्पीच, वॉयस एक्टिविटी डिटेक्शन और शोर रद्दीकरण — सभी स्थानीय रूप से चलते हैं। कोई क्लाउड API नहीं, कोई डेटा डिवाइस से बाहर नहीं जाता।

**[📚 Android दस्तावेज़](https://soniqo.audio/hi/getting-started/android)**

**[डेमो APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[मॉडल](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (Apple समकक्ष) · **[speech-core](https://github.com/soniqo/speech-core)** (पाइपलाइन इंजन + Linux/एम्बेडेड बिल्ड)

## डेमो

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="पूरा ऑफ़लाइन वॉइस एजेंट Android पर 1.2 GB में — YouTube पर डेमो देखें">
  </a>
</p>
<p align="center"><em><a href="control-demo/">control-demo</a> का पूरा कमांड लूप — Silero VAD → Parakeet STT → FunctionGemma → डिवाइस एक्शन → Pocket TTS जवाब — पूरी तरह ऑफ़लाइन, 1.2 GB RAM में</em></p>

## स्कोप

यह रिपॉज़िटरी **Android पैकेजिंग** है: Kotlin SDK, JNI ब्रिज, डेमो ऐप। C++ इंजन और ONNX मॉडल रैपर (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) [speech-core](https://github.com/soniqo/speech-core) में रहते हैं और एक git सबमॉड्यूल के माध्यम से शामिल किए जाते हैं। Linux / ऑटोमोटिव (Yocto, Qualcomm SA8295P/SA8255P) [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) पर रहता है।

## मॉडल

| मॉडल | कार्य | डाउनलोड | पीक मेमोरी | भाषाएँ |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/hi/guides/dictate) | स्ट्रीमिंग STT + EOU (डिफ़ॉल्ट) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/hi/guides/parakeet/android) | व्यापक STT (वैकल्पिक) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 25 यूरोपीय |
| [Nemotron-3.5 बहुभाषी](https://soniqo.audio/hi/guides/nemotron) | प्रॉम्प्ट-कंडीशन्ड स्ट्रीमिंग STT (वैकल्पिक) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | अभी मापा नहीं गया | 100+ (zh सहित) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | Offline STT + translation (optional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/hi/guides/kokoro/android) | टेक्स्ट-टू-स्पीच (डिफ़ॉल्ट) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | स्ट्रीमिंग टेक्स्ट-टू-स्पीच (वैकल्पिक, स्थिर Alba आवाज़) | ~126 MB | अभी मापा नहीं गया | अंग्रेज़ी |
| [Supertonic-3](https://soniqo.audio/hi/guides/supertonic) | टेक्स्ट-टू-स्पीच (LiteRT, फ़्लो-मैचिंग, G2P-free, 44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/hi/guides/vad/android) | वॉयस एक्टिविटी डिटेक्शन | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | कोई भी |
| [Sortformer 4-स्पीकर](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | स्ट्रीमिंग स्पीकर डायराइज़ेशन (वैकल्पिक) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | अभी मापा नहीं गया | कोई भी |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | स्पीकर एम्बेडिंग (वैकल्पिक) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | अभी मापा नहीं गया | कोई भी |
| [DeepFilterNet3](https://soniqo.audio/hi/guides/denoise/android) | शोर रद्दीकरण | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | डिफ़ॉल्ट रूप से लोड नहीं | कोई भी |
| [FunctionGemma 270M](https://soniqo.audio/hi/guides/function-calls) | ऑन-डिवाइस LLM — संरचित फ़ंक्शन / टूल कॉल | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | ऐप runtime पर निर्भर | EN-tuned |

मॉडल पहले लॉन्च पर `ModelManager.ensureModels()` के माध्यम से स्वचालित रूप से डाउनलोड होते हैं।

`SpeechConfig()` डिफ़ॉल्ट रूप से `SttModel.PARAKEET_EOU` और `TtsModel.KOKORO_SHORT_TURN` इस्तेमाल करता है, ताकि SDK इंटीग्रेशन और सिस्टम रिकग्नाइज़र कम-मेमोरी Android पथ पर रहें। डेमो ऐप `SttModel.PARAKEET` चुनता है, इसलिए इको और डिक्टेशन स्क्रीन 25 यूरोपीय भाषाओं वाले बड़े TDT मॉडल का उपयोग करती हैं।

दोनों Parakeet मॉडल हमेशा भाषा अपने-आप पहचानते हैं: वे `language` या `languageHints` स्वीकार नहीं करते और चीनी का समर्थन नहीं करते। मंदारिन सहित किसी एक भाषा को चुनने के लिए प्रॉम्प्ट-कंडीशन्ड Nemotron बैकएंड इस्तेमाल करें:

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
    language = "zh-CN", // या "zh-TW"
)
```

**Supertonic-3** एक ऑप्ट-इन उच्च-गुणवत्ता वाला बहुभाषी TTS है — इसे `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` के साथ चुनें (LiteRT बैकएंड आवश्यक)। होस्ट इसके चार नॉन-ऑटोरिग्रेसिव फ़्लो-मैचिंग ग्राफ़ ऑन-डिवाइस 44.1 kHz पर चलाता है; फ़्रंट-एंड G2P-free है (NFKD + Unicode इंडेक्स — कोई फ़ोनेमाइज़र नहीं), इसलिए सभी 31 भाषाएँ एक ही पथ से होकर गुजरती हैं।

लंबे वाक्य: बंडल में L=128 लेटेंट-विंडो वाले ग्राफ़ की एक वैकल्पिक जोड़ी भी शामिल है (`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 MB)। इसे `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)` (या `ModelManager.ensureTtsModels` पर उसी फ़्लैग) से डाउनलोड करें, तो speech-core 4.5 s की बेस विंडो से लंबे वाक्य को विभाजित करने के बजाय एक ही पास में सिंथेसाइज़ करता है; यह जोड़ी पहले उपयोग पर लोड होती है (+~360 MB RSS)। डिफ़ॉल्ट रूप से बंद।

Kokoro और Supertonic दोनों डायरेक्ट सिंथेसिस में प्रति-कॉल वॉइस प्रीसेट स्वीकार करते हैं — `pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`; Kokoro `af_heart`, `ff_siwis`, …) — और यही आर्ग्युमेंट `synthesizeStreaming` और `SpeechSynthesizer` पर भी मौजूद है। प्रीसेट केवल उसी कॉल पर लागू होता है; इसे छोड़ दें (या `""` पास करें) तो इंजन की डिफ़ॉल्ट आवाज़ बनी रहती है, और अज्ञात id पर अपवाद (exception) फेंका जाता है।

## डेमो आज़माएँ

[हस्ताक्षरित APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) डाउनलोड करें और किसी भी arm64 Android डिवाइस (8+) पर इंस्टॉल करें। डिफ़ॉल्ट कम-मेमोरी मॉडल बंडल (~500 MB) पहले लॉन्च पर स्वचालित रूप से डाउनलोड होता है।

## निर्भरता जोड़ें

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.23")
}
```

## Kotlin उपयोग

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

// माइक्रोफ़ोन से 16kHz मोनो float32 PCM फ़ीड करें
pipeline.pushAudio(samples)
```

### टर्न समाप्ति डिटेक्शन

VAD मौन पहचानता है, लेकिन यह नहीं बता सकता कि कोई विराम वक्ता की बात पूरी
करता है या नहीं। Smart Turn v3.2 एक वैकल्पिक ऑडियो-आधारित क्लासिफ़ायर है जो
मौजूदा टर्न के अंतिम आठ सेकंड देखकर अधूरे टर्न को खुला रखता है।

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

यह वैकल्पिक डाउनलोड स्थिर संस्करण वाला 11.1 MB INT8 ग्राफ़ जोड़ता है। हर
ऑडियो फ़्रेम पर नहीं, बल्कि VAD द्वारा पुष्टि किए गए प्रत्येक विराम के बाद एक
बार इनफ़रेंस चलता है। विराम अस्वीकार होने पर दोबारा शुरू हुई आवाज़ उसी टर्न में
रहती है; अधिकतम मौन सीमा अंत सुनिश्चित करती है। Smart Turn को Pipecat/Daily ने
बनाया है और यह BSD-2-Clause के तहत वितरित है।

### केवल वॉइस ऐक्टिविटी डिटेक्शन

जिन ऐप्स को सिर्फ़ यह जानना है कि कोई *कब* बोल रहा है — push-to-talk गेटिंग,
रिकॉर्डिंग सेगमेंटेशन, आवाज़ से जगाना — वे अकेले Silero लोड कर सकती हैं: डिफ़ॉल्ट
मॉडल सेट के ~500 MB की जगह 2 MB डाउनलोड और 10 MB से कम RAM। न STT, न TTS, न
पाइपलाइन।

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // कैप्चर किया गया सेगमेंट पाने के लिए चालू करें
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz मोनो float32, 512-सैंपल फ़्रेम
detector.pushAudio(samples)
// स्ट्रीम खत्म होने पर खुला सेगमेंट बंद करें
detector.flush()
```

`VadConfig` वही टर्न डिटेक्शन उजागर करता है जो पाइपलाइन भीतर इस्तेमाल करती है:
onset और offset थ्रेशोल्ड, न्यूनतम स्पीच अवधि, स्पीच-अंत की चुप्पी, और वह
प्री-स्पीच बफ़र जो पहला अक्षर बचाए रखता है। यह पथ
`ModelManager.ensureVadModels()` और अलग `models_vad/` कैश उपयोग करता है, इसलिए
STT/TTS बंडल कभी डाउनलोड नहीं होते।

### मीटिंग ट्रांसक्रिप्शन के बिल्डिंग ब्लॉक

जो ऐप अपनी रिकॉर्डिंग और सेगमेंटेशन खुद संभालते हैं — जैसे मीटिंग रिकॉर्डर या नोट लेने वाला ऐप — वे पाइपलाइन के बिना रिकग्नाइज़र, डायराइज़र और स्पीकर एनकोडर को अलग-अलग लोड कर सकते हैं। हर मॉडल का अपना डाउनलोड और कैश डायरेक्टरी है, और कोई भी प्रोडक्ट से जुड़ा फ़ैसला नहीं करता: ट्रांसक्राइबर टेक्स्ट लौटाता है, डायराइज़र हर फ़्रेम के लिए स्पीकर प्रायिकताएँ, और एनकोडर 192-आयामी आवाज़ वेक्टर। थ्रेशोल्ड, टर्न, स्पीकर लेबल और आवाज़ों का मिलान ऐप तय करता है।

```kotlin
// वैकल्पिक: उपयोगकर्ता का चुना हुआ मिरर, जो Hugging Face जैसी संरचना देता है।
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz मोनो float32; अब तक का टेक्स्ट
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [फ़्रेम x diarizer.speakers], अक्सर खाली
val tail = diarizer.endStream()           // रिकॉर्डिंग खत्म होने पर

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // कम से कम 2 सेकंड; FloatArray(192)
```

`TranscriberConfig.language` का डिफ़ॉल्ट `"auto"` है — speech-swift की तरह Nemotron का स्वचालित-भाषा प्रॉम्प्ट; `"en-US"`, `"pt_BR"` या सिर्फ़ `"fr"` एक भाषा तय करता है। हर ट्रांसक्रिप्ट में शब्द-स्तरीय टाइमिंग होती है, `beginStream` से सेकंड में, जो उस एनकोडर फ़्रेम से ली जाती है जिस पर हर टोकन निकला। Sortformer हर रिकॉर्डिंग के लिए एक स्ट्रीम है और ऑडियो से लगभग 30 सेकंड पीछे जवाब देता है, क्योंकि प्रकाशित एक्सपोर्ट हर कॉल में 27.2 सेकंड डिकोड करता है। `ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` तीनों सेट बैकग्राउंड में डाउनलोड करता है।

## स्रोत से बिल्ड करें

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 e2e परीक्षण
```

`./setup.sh` speech-core सबमॉड्यूल को इनिशियलाइज़ करता है और ONNX Runtime को
`./ort/` में डाउनलोड करता है।

## डेमो ऐप

[`app/`](app/) मॉड्यूल एक न्यूनतम वॉयस असिस्टेंट डेमो है जिसमें शामिल हैं:

- रीयल-टाइम VAD वेवफ़ॉर्म विज़ुअलाइज़ेशन
- इको मोड: स्पीच को ट्रांसक्राइब करता है और इसे वापस सिंथेसाइज़ करता है (कोई LLM नहीं)
- डिक्टेशन मोड: स्ट्रीमिंग आंशिक परिणाम
- वॉयस ओवरले: किसी भी ऐप में बोलकर लिखने के लिए फ़्लोटिंग माइक बटन
- इको और डिक्टेशन स्क्रीन में 25 यूरोपीय भाषाओं वाला Parakeet TDT STT
- `SpeechRecognizer` टेस्ट स्क्रीन — सिस्टम-वाइड वॉयस इनपुट पथ का परीक्षण करता है
- STT/TTS विलंबता प्रदर्शन के साथ चैट बबल UI

```bash
./gradlew :app:installDebug
```

### वॉयस ओवरले (किसी भी ऐप में बोलकर लिखें)

**वॉयस ओवरले** अन्य ऐप्स के ऊपर एक खींचकर हिलाने योग्य माइक बटन दिखाता है। टैप
करने पर यह **■ रोकें** / **✕ रद्द करें** में बदल जाता है: रोकें ट्रांसक्रिप्ट को उस टेक्स्ट
फ़ील्ड में लिख देता है जिस पर फ़ोकस है, और रद्द करें उसे हटा देता है। यदि कोई संपादन
योग्य फ़ील्ड फ़ोकस में नहीं है, तो टेक्स्ट खोने के बजाय क्लिपबोर्ड में चला जाता है।

तीन अनुमतियाँ चाहिए, हर एक की अपनी सिस्टम स्क्रीन है — सेटअप स्क्रीन दिखाती है कि कौन
सी अब भी बाकी हैं:

| अनुमति | क्यों |
| --- | --- |
| माइक्रोफ़ोन | ऑडियो कैप्चर करना |
| अन्य ऐप्स के ऊपर दिखाएँ | ऐप के बाहर बटन बनाना |
| सुलभता सेवा | दूसरे ऐप के टेक्स्ट फ़ील्ड में लिखना |

ओवरले विंडो जानबूझकर फ़ोकस नहीं लेती, ताकि बटन दबाते समय लक्ष्य फ़ील्ड का इनपुट
फ़ोकस बना रहे। टेक्स्ट `ACTION_SET_TEXT` से कर्सर पर डाला जाता है। जिन फ़ील्ड की
वास्तविक सामग्री पढ़ी नहीं जा सकती — कुछ ऐप्स अपने प्लेसहोल्डर को ही फ़ील्ड का टेक्स्ट
बताते हैं — उनमें पेस्ट करके लिखा जाता है, जिससे क्लिपबोर्ड पर जो था वह बदल जाता है;
इसके तुरंत बाद डिक्टेशन उससे मिटा दिया जाता है।

> Play Store के बजाय APK से इंस्टॉल कर रहे हैं? Android सुलभता टॉगल को तब तक रोकता
> है जब तक आप सेटिंग्स → ऐप्स → Speech → ⋮ → **प्रतिबंधित सेटिंग्स की अनुमति दें**
> नहीं चुनते।

### पूर्ण-पाइपलाइन कंट्रोल डेमो

अलग [`control-demo/`](control-demo/) ऐप पूरे एजेंट को स्थानीय रूप से चलाता है:
Silero VAD → Parakeet-EOU STT → FunctionGemma 270M टूल कॉल → Android डिवाइस
एक्शन → Pocket TTS। यह हर चरण की लेटेंसी दिखाता है और इस checkout के `:sdk`
से सीधे लिंक होता है, इसलिए स्थानीय स्पीच ऑप्टिमाइज़ेशन उपयोग होते हैं।

नवीनतम रिलीज़ से [हस्ताक्षरित Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
डाउनलोड करें, या सोर्स से डेवलपमेंट बिल्ड इंस्टॉल करें:

```bash
./gradlew :control-demo:installDebug
```

## सिस्टम वॉयस इनपुट (`RecognitionService`)

SDK एक उपयोग के लिए तैयार `audio.soniqo.speech.service.SpeechRecognitionService` शामिल करता है जो Android फ्रेमवर्क के `SpeechRecognizer` API से जुड़ता है — कोई कोड लिखने की आवश्यकता नहीं। एक बार आपका ऐप डिफ़ॉल्ट वॉयस रिकग्नाइज़र के रूप में चुना जाता है, कोई भी थर्ड-पार्टी ऐप जो `SpeechRecognizer.createSpeechRecognizer(context)` (बिना `ComponentName` के) कॉल करता है, आपकी पाइपलाइन के माध्यम से पूरी तरह से ऑन-डिवाइस STT प्राप्त करता है।

**1. `AndroidManifest.xml` में `RECORD_AUDIO` और सेवा घोषित करें:**

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

**2. `app/src/main/res/xml/recognition_service.xml` जोड़ें:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(वैकल्पिक रूप से `android:settingsActivity="..."` जोड़ें ताकि सिस्टम वॉयस-इनपुट पिकर में एक गियर आइकन दिखे।)

**3. सेवा को सिस्टम डिफ़ॉल्ट के रूप में सेट करें** (स्टॉक Android पर सेटिंग्स → सिस्टम → भाषाएँ और इनपुट → वॉयस इनपुट पिकर, या adb के माध्यम से):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. सत्यापित करें** डेमो ऐप का *Recognizer test* स्क्रीन चलाकर, जो `SpeechRecognizer.createSpeechRecognizer(ctx)` (बिना कंपोनेंट के) कॉल करता है और हर फ्रेमवर्क कॉलबैक को लॉग करता है — logcat के बिना binder राउंड-ट्रिप की पुष्टि के लिए उपयोगी।

सेवा `onCheckRecognitionSupport` (API 33+) को लागू करती है जो Parakeet-EOU द्वारा कवर की गई 25 BCP-47 आधार भाषाएँ लौटाती है, और समर्थित आधार भाषा से मेल खाने पर अनुरोधित सटीक क्षेत्रीय टैग भी लौटाती है। मॉडल मौजूद होने पर भाषाएँ `installedOnDeviceLanguage` के रूप में, और डाउनलोड से पहले `supportedOnDeviceLanguage` के रूप में चिह्नित होती हैं। सेवा कॉल करने वाले ऐप से ऑडियो फोकस नहीं लेती।

**सीमा:** Gboard, Samsung Keyboard और Google Assistant अपने स्वयं के पहचानकर्ता बंडल करते हैं और सिस्टम डिफ़ॉल्ट को छोड़ देते हैं। फ्रेमवर्क `SpeechRecognizer` API को स्पष्ट रूप से कॉल करने वाले ऐप (या उसके ऊपर अपना UI बनाने वाले) ही आपकी सेवा से होकर गुजरते हैं।

## सिस्टम टेक्स्ट-टू-स्पीच (`TextToSpeechService`)

डेमो ऐप `audio.soniqo.speech.service.SpeechTextToSpeechService` भी उजागर करता है, इसलिए Android सेटिंग्स → सिस्टम → भाषाएँ और इनपुट → टेक्स्ट-टू-स्पीच आउटपुट में इस ऐप को चुन सकता है। यह पथ `ModelManager.ensureTtsModels()` और अलग `models_tts/` कैश का उपयोग करता है, इसलिए फ्रेमवर्क TTS पूर्ण VAD/STT/enhancer पाइपलाइन बंडल के बजाय केवल Kokoro assets डाउनलोड करता है।

किसी अन्य ऐप से इंजन उजागर करने के लिए सेवा घोषित करें:

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

`app/src/main/res/xml/tts_engine.xml` जोड़ें:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## प्रदर्शन

Galaxy S23 Ultra (SM-S918B) पर केवल CPU के साथ मापा गया। RTF = दीवार-समय ÷ जनरेट किए गए ऑडियो की अवधि; कम बेहतर है और <1.0 रीयल-टाइम से तेज़ है।

| मॉडल | कार्य | RTF | लेटेंसी | पीक मेमोरी |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | स्ट्रीमिंग STT + EOU | 0.21 | streaming partials | 232 MB |
| Kokoro 82M पूर्ण ग्राफ़ (प्रकाशित, CPU के दो थ्रेड) | TTS | 1.81 | वाक्य-स्तर | ~604 MB |
| Kokoro 82M छोटा टर्न (3.0 सेकंड ग्राफ़, डिफ़ॉल्ट) | TTS | 0.75–0.88 | सीमित उत्तर; सुरक्षित पुनःप्रयास | ~527 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1 सेकंड TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | हर 32 मिलीसेकंड चंक पर <1 मिलीसेकंड | <10 MB |

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

प्रत्येक मॉडल क्लास सीधे संबंधित speech-core इंटरफ़ेस (`VADInterface`, `STTInterface`, `TTSInterface`, `EnhancerInterface`) को लागू करता है — JNI ब्रिज उन्हें इंस्टैंशिएट करता है और संदर्भ `VoicePipeline` को सौंपता है। कोई C-vtable अडैप्टर बॉइलरप्लेट नहीं।

## हार्डवेयर त्वरण

| चिपसेट | त्वरण |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU फ़ॉलबैक | XNNPACK |

ऑटोमोटिव Qualcomm SA8295P / SA8255P के लिए QNN (Hexagon DSP) के साथ, [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) देखें।

## संबंधित परियोजनाएँ

| रिपॉज़िटरी | स्कोप |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | क्रॉस-प्लेटफ़ॉर्म C++ पाइपलाइन इंजन + ONNX मॉडल रैपर + Linux/एम्बेडेड उदाहरण |
| **speech-android** | Android रैपर — speech-core के ऊपर Kotlin SDK + JNI ब्रिज |

## लाइसेंस

Apache 2.0
