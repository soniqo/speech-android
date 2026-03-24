cmake_minimum_required(VERSION 3.16)
project(speech_linux VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SPEECH_BUILD_DEMO "Build ALSA demo CLI" ON)
option(SPEECH_BUILD_TESTS "Build tests" ON)

# --- Paths ---
set(SDK_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../sdk/src/main/cpp")
set(SPEECH_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../speech-core" CACHE PATH "speech-core directory")
set(ORT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ort-linux" CACHE PATH "ONNX Runtime directory")

# --- speech-core (static library) ---
file(GLOB_RECURSE SPEECH_CORE_SOURCES "${SPEECH_CORE_DIR}/src/*.cpp")
add_library(speech_core STATIC ${SPEECH_CORE_SOURCES})
target_include_directories(speech_core PUBLIC "${SPEECH_CORE_DIR}/include")
target_compile_features(speech_core PUBLIC cxx_std_17)

# --- ONNX Runtime ---
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ORT_DIR}/lib/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ORT_DIR}/include"
)

# --- libspeech.so ---
add_library(speech SHARED
    src/speech.cpp
    ${SDK_CPP}/audio/mel.cpp
    ${SDK_CPP}/audio/fft.cpp
    ${SDK_CPP}/audio/stft.cpp
    ${SDK_CPP}/models/silero_vad.cpp
    ${SDK_CPP}/models/parakeet_stt.cpp
    ${SDK_CPP}/models/kokoro_tts.cpp
    ${SDK_CPP}/models/kokoro_phonemizer.cpp
    ${SDK_CPP}/models/deepfilter.cpp
)

target_include_directories(speech
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${SDK_CPP}
        ${SDK_CPP}/models
        ${ORT_DIR}/include
        ${SPEECH_CORE_DIR}/include
)

target_link_libraries(speech PRIVATE speech_core onnxruntime)

# --- Demo CLI ---
if(SPEECH_BUILD_DEMO)
    add_executable(speech_demo demo/main.cpp)
    target_link_libraries(speech_demo PRIVATE speech)
    target_include_directories(speech_demo PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

    find_library(ALSA_LIB asound)
    if(ALSA_LIB)
        target_link_libraries(speech_demo PRIVATE ${ALSA_LIB})
        target_compile_definitions(speech_demo PRIVATE HAS_ALSA=1)
    endif()
endif()

# --- Tests ---
if(SPEECH_BUILD_TESTS)
    enable_testing()
    add_executable(speech_test tests/test_pipeline.cpp)
    target_link_libraries(speech_test PRIVATE speech)
    target_include_directories(speech_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME pipeline_test COMMAND speech_test)
endif()
