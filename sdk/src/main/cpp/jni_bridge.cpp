#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdint>

#include <speech_core/speech_core_c.h>
#include "models/onnx_engine.h"
#include "models/silero_vad.h"
#include "models/parakeet_stt.h"
#include "models/kokoro_tts.h"
#include "models/deepfilter.h"

#define LOG_TAG "Speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Pipeline handle — owns all native objects for one pipeline instance
// ---------------------------------------------------------------------------

struct PipelineHandle {
    sc_pipeline_t pipeline = nullptr;
    SileroVad* vad = nullptr;
    ParakeetStt* stt = nullptr;
    KokoroTts* tts = nullptr;
    DeepFilterEnhancer* enhancer = nullptr;

    JavaVM* jvm = nullptr;
    jobject callback = nullptr;
    jmethodID on_event_mid = nullptr;

    ~PipelineHandle() {
        if (pipeline) sc_pipeline_destroy(pipeline);
        delete enhancer;
        delete tts;
        delete stt;
        delete vad;
    }
};

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
// speech-core vtable adapters
// ---------------------------------------------------------------------------

// --- VAD ---

static float vad_process_chunk(void* ctx, const float* samples, size_t len) {
    return static_cast<SileroVad*>(ctx)->process_chunk(samples, len);
}
static void vad_reset(void* ctx) {
    static_cast<SileroVad*>(ctx)->reset();
}
static int vad_sample_rate(void* ctx) {
    return static_cast<SileroVad*>(ctx)->input_sample_rate();
}
static size_t vad_chunk_size(void* ctx) {
    return static_cast<SileroVad*>(ctx)->chunk_size();
}

// --- STT ---

static sc_transcription_result_t stt_transcribe(
    void* ctx, const float* audio, size_t len, int sr)
{
    auto* stt = static_cast<ParakeetStt*>(ctx);
    auto r = stt->transcribe(audio, len, sr);

    // Static buffers — valid until next call (per C API contract)
    static thread_local std::string text_buf;
    static thread_local std::string lang_buf;
    text_buf = std::move(r.text);
    lang_buf = std::move(r.language);

    return {
        .text = text_buf.c_str(),
        .language = lang_buf.empty() ? nullptr : lang_buf.c_str(),
        .confidence = r.confidence,
        .start_time = 0.0f,
        .end_time = 0.0f,
    };
}
static int stt_sample_rate(void* ctx) {
    return static_cast<ParakeetStt*>(ctx)->input_sample_rate();
}

static void stt_begin_stream(void* ctx, int sample_rate) {
    static_cast<ParakeetStt*>(ctx)->begin_stream(sample_rate);
}

static sc_partial_result_t stt_push_chunk(void* ctx, const float* audio, size_t len) {
    auto* stt = static_cast<ParakeetStt*>(ctx);
    auto r = stt->push_chunk(audio, len);
    static thread_local std::string text_buf;
    static thread_local std::string lang_buf;
    text_buf = std::move(r.text);
    lang_buf = std::move(r.language);
    return {
        .text = text_buf.c_str(),
        .language = lang_buf.empty() ? nullptr : lang_buf.c_str(),
        .confidence = r.confidence,
    };
}

static void stt_flush_stream(void* ctx) {
    static_cast<ParakeetStt*>(ctx)->flush_stream();
}

static sc_transcription_result_t stt_end_stream(void* ctx) {
    auto* stt = static_cast<ParakeetStt*>(ctx);
    auto r = stt->end_stream();
    static thread_local std::string text_buf;
    static thread_local std::string lang_buf;
    text_buf = std::move(r.text);
    lang_buf = std::move(r.language);
    return {
        .text = text_buf.c_str(),
        .language = lang_buf.empty() ? nullptr : lang_buf.c_str(),
        .confidence = r.confidence,
        .start_time = 0.0f,
        .end_time = 0.0f,
    };
}

static void stt_cancel_stream(void* ctx) {
    static_cast<ParakeetStt*>(ctx)->cancel_stream();
}

// --- TTS ---

static void tts_synthesize(
    void* ctx, const char* text, const char* language,
    sc_tts_chunk_fn on_chunk, void* chunk_ctx)
{
    auto* tts = static_cast<KokoroTts*>(ctx);
    tts->synthesize(text, language, on_chunk, chunk_ctx);
}
static int tts_sample_rate(void* ctx) {
    return static_cast<KokoroTts*>(ctx)->output_sample_rate();
}
static void tts_cancel(void* ctx) {
    static_cast<KokoroTts*>(ctx)->cancel();
}

// --- Enhancer ---

static void enhancer_enhance(
    void* ctx, const float* input, size_t len, int sr, float* output)
{
    static_cast<DeepFilterEnhancer*>(ctx)->enhance(input, len, sr, output);
}
static int enhancer_sample_rate(void* ctx) {
    return static_cast<DeepFilterEnhancer*>(ctx)->input_sample_rate();
}

// ---------------------------------------------------------------------------
// Event callback → Kotlin
// ---------------------------------------------------------------------------

static void on_pipeline_event(const sc_event_t* event, void* context) {
    auto* handle = static_cast<PipelineHandle*>(context);
    LOGI("event type=%d text='%.60s' audio=%zu stt=%.0fms tts=%.0fms",
         event->type, event->text ? event->text : "",
         event->audio_data_length, event->stt_duration_ms, event->tts_duration_ms);
    if (!handle->callback) return;

    JNIEnv* env = get_env(handle->jvm);
    if (!env) return;

    jstring text = event->text
        ? env->NewStringUTF(event->text) : nullptr;

    jbyteArray audio = nullptr;
    if (event->audio_data && event->audio_data_length > 0) {
        audio = env->NewByteArray(static_cast<jsize>(event->audio_data_length));
        env->SetByteArrayRegion(audio, 0,
            static_cast<jsize>(event->audio_data_length),
            reinterpret_cast<const jbyte*>(event->audio_data));
    }

    // void onEvent(int type, String text, byte[] audio,
    //              float confidence, float sttMs, float ttsMs)
    env->CallVoidMethod(handle->callback, handle->on_event_mid,
        static_cast<jint>(event->type),
        text, audio,
        event->confidence,
        event->stt_duration_ms,
        event->tts_duration_ms);

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
    jobject callback,
    jboolean emitPartialTranscriptions, jfloat partialTranscriptionInterval)
{
    auto dir = jstring_to_string(env, modelDir);
    bool nnapi = useNnapi;
    std::string suffix = useInt8 ? "-int8" : "";

    auto* h = new PipelineHandle();
    env->GetJavaVM(&h->jvm);
    h->callback = env->NewGlobalRef(callback);

    // Cache event method ID
    jclass cls = env->GetObjectClass(callback);
    h->on_event_mid = env->GetMethodID(cls, "onEvent",
        "(ILjava/lang/String;[BFFF)V");

    try {
        // Load models
        h->vad = new SileroVad(dir + "/silero-vad.onnx", false);
        h->stt = new ParakeetStt(
            dir + "/parakeet-encoder" + suffix + ".onnx",
            dir + "/parakeet-decoder-joint" + suffix + ".onnx",
            dir + "/vocab.json",
            nnapi);
        h->tts = new KokoroTts(
            dir + "/kokoro-e2e.onnx",
            dir + "/voices",
            dir,
            nnapi);

        // Build vtables
        sc_vad_vtable_t vad_vt = {
            .context = h->vad,
            .process_chunk = vad_process_chunk,
            .reset = vad_reset,
            .input_sample_rate = vad_sample_rate,
            .chunk_size = vad_chunk_size,
        };

        sc_stt_vtable_t stt_vt = {};
        stt_vt.context = h->stt;
        stt_vt.transcribe = stt_transcribe;
        stt_vt.input_sample_rate = stt_sample_rate;
        stt_vt.begin_stream = stt_begin_stream;
        stt_vt.push_chunk = stt_push_chunk;
        stt_vt.flush_stream = stt_flush_stream;
        stt_vt.end_stream = stt_end_stream;
        stt_vt.cancel_stream = stt_cancel_stream;

        sc_tts_vtable_t tts_vt = {};
        tts_vt.context = h->tts;
        tts_vt.synthesize = tts_synthesize;
        tts_vt.output_sample_rate = tts_sample_rate;
        tts_vt.cancel = tts_cancel;

        // Pipeline config
        sc_config_t config = sc_config_default();
        config.min_silence_duration = 0.5f;
        config.eager_stt = false;
        config.min_speech_duration = 0.15f;
        config.post_playback_guard = 0.15f;
        config.emit_partial_transcriptions = emitPartialTranscriptions;
        config.partial_transcription_interval = partialTranscriptionInterval;

        config.mode = SC_MODE_ECHO;
        h->pipeline = sc_pipeline_create(
            stt_vt, tts_vt, nullptr, vad_vt,
            config, on_pipeline_event, h);

        // Note: DeepFilterNet3 noise cancellation is disabled in the pipeline.
        // DFN operates at 48kHz but the pipeline pushes 16kHz audio — running
        // DFN without resampling produces artifacts. Needs 16k→48k→DFN→48k→16k
        // resample chain before it can be re-enabled. See issue #12.
        // The model is still downloaded for future use.

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
        delete h;
        jclass ex_cls = env->FindClass("java/lang/RuntimeException");
        if (ex_cls) {
            std::string msg = std::string("Native pipeline failed: ") + e.what();
            env->ThrowNew(ex_cls, msg.c_str());
        }
        return 0;
    }

    return reinterpret_cast<jlong>(h);
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
    if (h && h->pipeline) sc_pipeline_start(h->pipeline);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) sc_pipeline_stop(h->pipeline);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativePushAudio(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jfloatArray samples, jint count)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return;

    float* data = env->GetFloatArrayElements(samples, nullptr);
    sc_pipeline_push_audio(h->pipeline, data, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeResumeListen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (h && h->pipeline) sc_pipeline_resume_listening(h->pipeline);
}

JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeGetState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    auto* h = reinterpret_cast<PipelineHandle*>(handle);
    if (!h || !h->pipeline) return SC_STATE_IDLE;
    return sc_pipeline_state(h->pipeline);
}

} // extern "C"
