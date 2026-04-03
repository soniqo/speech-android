#!/bin/bash
set -euo pipefail

# Download ONNX models for testing.
# Usage: ./download_models.sh [output_dir]

BASE_URL="https://huggingface.co/aufklarer"
OUT="${1:-$(dirname "$0")/models}"
mkdir -p "$OUT/voices"

FILES=(
    "Silero-VAD-v5-ONNX/silero-vad.onnx"
    "Parakeet-TDT-v3-ONNX/parakeet-encoder-int8.onnx"
    "Parakeet-TDT-v3-ONNX/parakeet-decoder-joint-int8.onnx"
    "Parakeet-TDT-v3-ONNX/vocab.json"
    "Kokoro-82M-ONNX/kokoro-int8.onnx"
    "Kokoro-82M-ONNX/vocab_index.json"
    "Kokoro-82M-ONNX/us_gold.json"
    "Kokoro-82M-ONNX/us_silver.json"
    "Kokoro-82M-ONNX/voices/af_heart.bin"
)

for entry in "${FILES[@]}"; do
    repo="${entry%%/*}"
    file="${entry#*/}"
    dest="$OUT/$file"
    if [ -f "$dest" ]; then
        continue
    fi
    echo "Downloading $file..."
    curl -sL -o "$dest" "$BASE_URL/$repo/resolve/main/$file"
done

echo "Models ready in $OUT"
