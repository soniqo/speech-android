@echo off
REM Set up Python environment for ONNX to TFLite conversion
REM Run once on Windows before using convert_models.bat

echo Setting up conversion environment...

python -m venv .venv
call .venv\Scripts\activate.bat

pip install --upgrade pip
pip install onnx==1.16.0
pip install onnx-tf==1.10.0
pip install tensorflow==2.16.1
pip install protobuf==3.20.3

echo.
echo Environment ready. Activate with:
echo   .venv\Scripts\activate.bat
echo.
echo Then run:
echo   convert_models.bat C:\path\to\onnx\models C:\path\to\tflite\output
