cmake_minimum_required(VERSION 3.22.1)
project(speech_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# speech-core (git submodule)
# ---------------------------------------------------------------------------

if(NOT DEFINED SPEECH_CORE_DIR)
    message(FATAL_ERROR "SPEECH_CORE_DIR must be set")
endif()

set(SPEECH_CORE_SOURCES
    ${SPEECH_CORE_DIR}/src/pipeline/voice_pipeline.cpp
    ${SPEECH_CORE_DIR}/src/pipeline/turn_detector.cpp
    ${SPEECH_CORE_DIR}/src/pipeline/speech_queue.cpp
    ${SPEECH_CORE_DIR}/src/pipeline/conversation_context.cpp
    ${SPEECH_CORE_DIR}/src/vad/streaming_vad.cpp
    ${SPEECH_CORE_DIR}/src/audio/audio_buffer.cpp
    ${SPEECH_CORE_DIR}/src/audio/resampler.cpp
    ${SPEECH_CORE_DIR}/src/audio/pcm_codec.cpp
    ${SPEECH_CORE_DIR}/src/tools/tool_registry.cpp
    ${SPEECH_CORE_DIR}/src/tools/intent_matcher.cpp
    ${SPEECH_CORE_DIR}/src/tools/tool_executor.cpp
    ${SPEECH_CORE_DIR}/src/speech_core_c.cpp
)

add_library(speech_core STATIC ${SPEECH_CORE_SOURCES})
target_include_directories(speech_core PUBLIC ${SPEECH_CORE_DIR}/include)
target_compile_options(speech_core PRIVATE -O2)

# ---------------------------------------------------------------------------
# ONNX Runtime (prebuilt)
# ---------------------------------------------------------------------------

if(NOT DEFINED ORT_DIR)
    message(FATAL_ERROR "ORT_DIR must be set (path to onnxruntime with include/ and lib/)")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${ORT_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so
)

# ---------------------------------------------------------------------------
# speech-android native library
# ---------------------------------------------------------------------------

add_library(speech_android SHARED
    jni_bridge.cpp
    audio/mel.cpp
    audio/fft.cpp
    audio/stft.cpp
    models/silero_vad.cpp
    models/parakeet_stt.cpp
    models/kokoro_tts.cpp
    models/kokoro_phonemizer.cpp
    models/kokoro_multilingual.cpp
    models/deepfilter.cpp
)

target_include_directories(speech_android PRIVATE
    ${ORT_DIR}/include
    ${SPEECH_CORE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(speech_android
    speech_core
    onnxruntime
    android
    log
)
