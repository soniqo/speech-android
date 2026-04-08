@echo off
REM Convert ONNX models to TFLite for LiteRT backend
REM Run on Windows with Python 3.10+ and CUDA (optional for faster conversion)
REM
REM Prerequisites:
REM   pip install onnx onnx-tf tensorflow tf2onnx
REM
REM Usage:
REM   convert_models.bat C:\models\onnx C:\models\tflite

setlocal enabledelayedexpansion

set ONNX_DIR=%1
set TFLITE_DIR=%2

if "%ONNX_DIR%"=="" (
    echo Usage: convert_models.bat ^<onnx_dir^> ^<tflite_dir^>
    echo Example: convert_models.bat C:\models\onnx C:\models\tflite
    exit /b 1
)

if "%TFLITE_DIR%"=="" (
    set TFLITE_DIR=%ONNX_DIR%\..\tflite
)

mkdir "%TFLITE_DIR%" 2>nul

echo ============================================
echo ONNX to TFLite Model Conversion
echo ============================================
echo Source: %ONNX_DIR%
echo Output: %TFLITE_DIR%
echo.

REM --- Silero VAD ---
echo [1/5] Converting Silero VAD...
python "%~dp0convert_single.py" ^
    --input "%ONNX_DIR%\silero-vad.onnx" ^
    --output "%TFLITE_DIR%\silero-vad.tflite" ^
    --quantize none
if errorlevel 1 (
    echo FAILED: Silero VAD
    exit /b 1
)

REM --- Parakeet Encoder ---
echo [2/5] Converting Parakeet Encoder (INT8)...
python "%~dp0convert_single.py" ^
    --input "%ONNX_DIR%\parakeet-encoder-int8.onnx" ^
    --output "%TFLITE_DIR%\parakeet-encoder-int8.tflite" ^
    --quantize int8
if errorlevel 1 (
    echo FAILED: Parakeet Encoder
    exit /b 1
)

REM --- Parakeet Decoder-Joint ---
echo [3/5] Converting Parakeet Decoder-Joint (INT8)...
python "%~dp0convert_single.py" ^
    --input "%ONNX_DIR%\parakeet-decoder-joint-int8.onnx" ^
    --output "%TFLITE_DIR%\parakeet-decoder-joint-int8.tflite" ^
    --quantize int8
if errorlevel 1 (
    echo FAILED: Parakeet Decoder-Joint
    exit /b 1
)

REM --- Kokoro TTS ---
echo [4/5] Converting Kokoro TTS...
python "%~dp0convert_single.py" ^
    --input "%ONNX_DIR%\kokoro-e2e.onnx" ^
    --output "%TFLITE_DIR%\kokoro-e2e.tflite" ^
    --external_data "%ONNX_DIR%\kokoro-e2e.onnx.data" ^
    --quantize none
if errorlevel 1 (
    echo FAILED: Kokoro TTS
    exit /b 1
)

REM --- DeepFilterNet3 ---
echo [5/5] Converting DeepFilterNet3...
python "%~dp0convert_single.py" ^
    --input "%ONNX_DIR%\deepfilter.onnx" ^
    --output "%TFLITE_DIR%\deepfilter.tflite" ^
    --quantize none
if errorlevel 1 (
    echo FAILED: DeepFilterNet3
    exit /b 1
)

echo.
echo ============================================
echo All models converted successfully!
echo ============================================
echo Output: %TFLITE_DIR%
dir "%TFLITE_DIR%\*.tflite"
