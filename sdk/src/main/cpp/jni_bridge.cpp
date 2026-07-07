#include <jni.h>
#include <android/log.h>

#include <speech_core/models/deepfilter.h>
#include <speech_core/models/kokoro_tts.h>
#include <speech_core/models/onnx_engine.h>
#include <speech_core/models/onnx_nemotron_streaming_stt.h>
#include <speech_core/models/parakeet_stt.h>
#include <speech_core/models/nemotron_multilingual_stt.h>
#include <speech_core/models/silero_vad.h>
#ifdef SPEECH_ANDROID_WITH_LITERT
#include <speech_core/models/litert_nemotron_multilingual_stt.h>
#include <speech_core/models/litert_supertonic_tts.h>
#endif
#include <speech_core/interfaces.h>
#include <speech_core/pipeline/agent_config.h>
#include <speech_core/pipeline/voice_pipeline.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "Speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Pipeline handle
//
// speech_core::* model wrappers directly implement the speech_core interfaces
// (VADInterface / STTInterface / TTSInterface / EnhancerInterface), so the
// JNI bridge constructs them and hands references to VoicePipeline. No
// C-vtable adapters needed — the entire vtable boilerplate that used to live
// here was deleted in this change.
// ---------------------------------------------------------------------------

struct PipelineHandle {
    std::unique_ptr<speech_core::SileroVad> vad;
    std::unique_ptr<speech_core::STTInterface> stt;  // Parakeet-EOU, Parakeet TDT, or Nemotron
    std::unique_ptr<speech_core::TTSInterface> tts;  // Kokoro (ONNX) or Supertonic (LiteRT)
    std::unique_ptr<speech_core::DeepFilterEnhancer> enhancer;
    std::unique_ptr<speech_core::VoicePipeline> pipeline;

    JavaVM* jvm = nullptr;
    jobject callback = nullptr;
    jmethodID on_event_mid = nullptr;
};

struct SynthesizerHandle {
    std::unique_ptr<speech_core::TTSInterface> tts;
    std::mutex mutex;
};

static constexpr int STT_PARAKEET = 0;
static constexpr int STT_NEMOTRON_MULTILINGUAL = 1;
static constexpr int STT_PARAKEET_EOU = 2;
static constexpr int BACKEND_ONNX = 0;
static constexpr int BACKEND_LITERT = 1;
static constexpr int TTS_KOKORO = 0;
static constexpr int TTS_SUPERTONIC = 1;

static std::unique_ptr<speech_core::TTSInterface> create_tts(
    const std::string& dir, bool nnapi, int ttsModel)
{
    if (ttsModel == TTS_SUPERTONIC) {
#ifdef SPEECH_ANDROID_WITH_LITERT
        // Assets from soniqo/Supertonic-3-LiteRT: the four graphs + the G2P-free tokenizer
        // (unicode_indexer.json + tts.json in modelDir) + voice_styles/.
        return std::make_unique<speech_core::LiteRTSupertonicTts>(
            dir + "/duration_predictor.tflite",
            dir + "/text_encoder.tflite",
            dir + "/vector_estimator.tflite",
            dir + "/vocoder.tflite",
            dir,
            dir + "/voice_styles",
            nnapi);
#else
        throw std::runtime_error("Supertonic TTS requires the LiteRT backend (not built into this SDK)");
#endif
    }

    return std::make_unique<speech_core::KokoroTts>(
        dir + "/kokoro-e2e.onnx",
        dir + "/voices",
        dir,
        nnapi);
}

// ---------------------------------------------------------------------------
// JNI thread helper
// ---------------------------------------------------------------------------

static JNIEnv* get_env(JavaVM* jvm) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

// ---------------------------------------------------------------------------
// Pipeline event → Kotlin onEvent
//
// Kotlin signature unchanged:
//   void onEvent(int type, String text, byte[] audio,
//                float confidence, float sttMs, float ttsMs)
// ---------------------------------------------------------------------------

// Map speech_core::EventType → the int values the Kotlin side expects.
//
// Kotlin's SpeechPipeline.kt switches on raw ints inherited from the original
// C ABI (sc_event_t.type), whose ordering differs from speech_core::EventType:
// the C ABI had ResponseAudioDelta=7 / ResponseDone=8, the enum has them
// swapped. Map explicitly so renumbering speech_core::EventType in the future
// can't silently break the Kotlin event stream.
static jint to_kotlin_event(speech_core::EventType t) {
    using ET = speech_core::EventType;
    switch (t) {
        case ET::SessionCreated:         return 0;
        case ET::SpeechStarted:          return 1;
        case ET::SpeechEnded:            return 2;
        case ET::PartialTranscription:   return 3;
        case ET::TranscriptionCompleted: return 4;
        case ET::ResponseCreated:        return 5;
        case ET::ResponseInterrupted:    return 6;
        case ET::ResponseAudioDelta:     return 7;
        case ET::ResponseDone:           return 8;
        case ET::ToolCallStarted:        return 9;
        case ET::ToolCallCompleted:      return 10;
        case ET::Error:                  return 11;
    }
    return -1;
}

static void dispatch_event(PipelineHandle* h,
                           const speech_core::PipelineEvent& event) {
    LOGI("event type=%d text='%.60s' audio=%zu stt=%.0fms tts=%.0fms",
         static_cast<int>(event.type), event.text.c_str(),
         event.audio_data.size(), event.stt_duration_ms,
         event.tts_duration_ms);

    if (!h->callback) return;

    JNIEnv* env = get_env(h->jvm);
    if (!env) return;

    jstring text = !event.text.empty()
        ? env->NewStringUTF(event.text.c_str()) : nullptr;

    jbyteArray audio = nullptr;
    if (!event.audio_data.empty()) {
        audio = env->NewByteArray(static_cast<jsize>(event.audio_data.size()));
        env->SetByteArrayRegion(audio, 0,
            static_cast<jsize>(event.audio_data.size()),
            reinterpret_cast<const jbyte*>(event.audio_data.data()));
    }

    env->CallVoidMethod(h->callback, h->on_event_mid,
        to_kotlin_event(event.type),
        text, audio,
        event.confidence,
        event.stt_duration_ms,
        event.tts_duration_ms);

    if (audio) env->DeleteLocalRef(audio);
    if (text) env->DeleteLocalRef(text);
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

static std::string jstring_to_string(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreate(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelDir, jboolean useNnapi, jboolean useInt8,
    jint sttModel, jint sttBackend, jint ttsModel, jstring language,
    jobject callback,
    jboolean emitPartialTranscriptions, jfloat partialTranscriptionInterval)
{
    auto dir = jstring_to_string(env, modelDir);
    bool nnapi = useNnapi;
    std::string suffix = useInt8 ? "-int8" : "";
    std::string lang = jstring_to_string(env, language);

    auto h = std::make_unique<PipelineHandle>();
    env->GetJavaVM(&h->jvm);
    h->callback = env->NewGlobalRef(callback);

    // Cache event method ID
    jclass cls = env->GetObjectClass(callback);
    h->on_event_mid = env->GetMethodID(cls, "onEvent",
        "(ILjava/lang/String;[BFFF)V");

    try {
        // Load models
        h->vad = std::make_unique<speech_core::SileroVad>(
            dir + "/silero-vad.onnx", /*hw_accel=*/false);
        // STT — Parakeet-EOU low-memory streaming, Parakeet TDT v3, or
        // Nemotron-3.5 multilingual (prompt-conditioned) on ONNX/LiteRT.
        if (sttModel == STT_NEMOTRON_MULTILINGUAL) {
            if (sttBackend == BACKEND_LITERT) {
#ifdef SPEECH_ANDROID_WITH_LITERT
                auto m = std::make_unique<speech_core::LiteRTNemotronMultilingualStt>(
                    dir + "/nemotron-multilingual-encoder.tflite",
                    dir + "/nemotron-multilingual-decoder.tflite",
                    dir + "/nemotron-multilingual-joint.tflite",
                    dir + "/vocab.json", dir + "/languages.json", nnapi);
                if (lang != "auto" && !lang.empty()) m->set_language(lang);
                h->stt = std::move(m);
#else
                throw std::runtime_error("LiteRT STT backend not built into this SDK");
#endif
            } else {
                auto m = std::make_unique<speech_core::NemotronMultilingualStt>(
                    dir + "/encoder.onnx", dir + "/decoder.onnx", dir + "/joint.onnx",
                    dir + "/vocab.json", dir + "/languages.json", nnapi);
                if (lang != "auto" && !lang.empty()) m->set_language(lang);
                h->stt = std::move(m);
            }
        } else if (sttModel == STT_PARAKEET_EOU) {
            h->stt = std::make_unique<speech_core::OnnxNemotronStreamingStt>(
                dir + "/parakeet-eou-encoder.onnx",
                dir + "/parakeet-eou-decoder.onnx",
                dir + "/parakeet-eou-joint.onnx",
                dir + "/vocab.json",
                nnapi);
        } else {
            h->stt = std::make_unique<speech_core::ParakeetStt>(
                dir + "/parakeet-encoder" + suffix + ".onnx",
                dir + "/parakeet-decoder-joint" + suffix + ".onnx",
                dir + "/vocab.json",
                nnapi);
        }
        // TTS — Kokoro (ONNX, 24 kHz) or Supertonic-3 (LiteRT flow-matching, 44.1 kHz, G2P-free).
        h->tts = create_tts(dir, nnapi, ttsModel);

        speech_core::AgentConfig cfg;
        cfg.vad.min_silence_duration = 0.5f;
        cfg.vad.min_speech_duration = 0.15f;
        cfg.eager_stt = false;
        cfg.post_playback_guard = 0.15f;
        cfg.emit_partial_transcriptions = emitPartialTranscriptions;
        cfg.partial_transcription_interval = partialTranscriptionInterval;
        cfg.mode = speech_core::AgentConfig::Mode::Echo;

        // Note: DeepFilterNet3 noise cancellation is disabled in the pipeline.
        // DFN operates at 48 kHz but the pipeline pushes 16 kHz audio —
        // running DFN without resampling produces artifacts. Needs a
        // 16k→48k→DFN→48k→16k resample chain before it can be re-enabled.
        // See issue #12. The model is still downloaded for future use.

        PipelineHandle* raw = h.get();
        h->pipeline = std::make_unique<speech_core::VoicePipeline>(
            *h->stt, *h->tts, /*llm=*/nullptr, *h->vad, cfg,
            [raw](const speech_core::PipelineEvent& e) { dispatch_event(raw, e); });

        auto& engine = OnnxEngine::get();
        if (engine.had_nnapi_fallback()) {
            LOGI("Pipeline created with NNAPI fallback to CPU: %s",
                 engine.nnapi_fallback_reason().c_str());
        } else {
            LOGI("Pipeline created (NNAPI=%d)", nnapi);
        }
    } catch (const std::exception& e) {
        LOGE("Pipeline creation failed: %s", e.what());
        if (h->callback) env->DeleteGlobalRef(h->callback);
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native pipeline failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jstring JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeNnapiFallbackReason(
    JNIEnv* env, jobject /*thiz*/)
{
    auto& engine = OnnxEngine::get();
    if (engine.had_nnapi_fallback()) {
        return env->NewStringUTF(engine.nnapi_fallback_reason().c_str());
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h) {
        if (h->callback) env->DeleteGlobalRef(h->callback);
        delete h;
    }
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->start();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->stop();
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePushAudio(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jfloatArray samples, jint count)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return;

    float* data = env->GetFloatArrayElements(samples, nullptr);
    h->pipeline->push_audio(data, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeResumeListen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) h->pipeline->resume_listening();
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeGetState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return 0;
    return static_cast<jint>(h->pipeline->state());
}

JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreateSynthesizer(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelDir, jboolean useNnapi, jint ttsModel)
{
    auto dir = jstring_to_string(env, modelDir);
    auto h = std::make_unique<SynthesizerHandle>();

    try {
        h->tts = create_tts(dir, useNnapi, ttsModel);
        auto& engine = OnnxEngine::get();
        if (engine.had_nnapi_fallback()) {
            LOGI("Synthesizer created with NNAPI fallback to CPU: %s",
                 engine.nnapi_fallback_reason().c_str());
        } else {
            LOGI("Synthesizer created (NNAPI=%d)", static_cast<int>(useNnapi));
        }
    } catch (const std::exception& e) {
        LOGE("Synthesizer creation failed: %s", e.what());
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native synthesizer failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroySynthesizer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    delete h;
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStopSynthesizer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (h && h->tts) h->tts->cancel();
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesizerSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts) return 0;
    return static_cast<jint>(h->tts->output_sample_rate());
}

JNIEXPORT jbyteArray JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring language)
{
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts) {
        jclass ex_cls = env->FindClass("java/lang/IllegalStateException");
        if (ex_cls) env->ThrowNew(ex_cls, "Native synthesizer is closed");
        return nullptr;
    }

    std::string input = jstring_to_string(env, text);
    std::string lang = jstring_to_string(env, language);
    std::vector<int16_t> pcm;

    try {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->tts->synthesize(input, lang, [&pcm](const float* samples, size_t length, bool /*is_final*/) {
            pcm.reserve(pcm.size() + length);
            for (size_t i = 0; i < length; ++i) {
                const float clamped = std::max(-1.0f, std::min(1.0f, samples[i]));
                pcm.push_back(static_cast<int16_t>(std::lrintf(clamped * 32767.0f)));
            }
        });
    } catch (const std::exception& e) {
        LOGE("Synthesis failed: %s", e.what());
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native synthesis failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return nullptr;
    }

    const jsize byte_count = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    jbyteArray out = env->NewByteArray(byte_count);
    if (byte_count > 0) {
        env->SetByteArrayRegion(
            out, 0, byte_count,
            reinterpret_cast<const jbyte*>(pcm.data()));
    }
    return out;
}

} // extern "C"
