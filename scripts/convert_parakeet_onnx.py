#!/usr/bin/env python3
"""
Convert NVIDIA Parakeet-TDT v3 from NeMo to ONNX with INT8 quantization.

Based on the CoreML export script from speech-swift.
Produces encoder + fused decoder_joint ONNX models matching the Android JNI bridge.

Usage:
  pip install nemo_toolkit[asr] onnxruntime onnx
  python scripts/convert_parakeet_onnx.py --output-dir ./parakeet-onnx
  python scripts/convert_parakeet_onnx.py --output-dir ./parakeet-onnx --quantize

Publish to HuggingFace:
  huggingface-cli upload aufklarer/Parakeet-TDT-v3-ONNX ./parakeet-onnx
"""

import argparse
import gc
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn


def load_nemo_model():
    """Load the Parakeet-TDT model from NeMo."""
    import nemo.collections.asr as nemo_asr
    model = nemo_asr.models.EncDecRNNTBPEModel.from_pretrained(
        "nvidia/parakeet-tdt-0.6b-v3"
    )
    model.eval()
    return model


class EncoderWrapper(nn.Module):
    """Wraps the FastConformer encoder for ONNX export.

    Input:  audio_signal [1, 128, T], length [1]
    Output: outputs [1, 1024, T'], encoded_lengths [1]

    Keeps [B, C, T] layout (channels-first) to match Android C++ code.
    """
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, audio_signal, length):
        encoded, encoded_length = self.encoder(audio_signal=audio_signal, length=length)
        # NeMo encoder already outputs [B, C, T] — keep as-is for Android
        return encoded, encoded_length


class DecoderJointWrapper(nn.Module):
    """Fused decoder + joint network for ONNX export.

    Matches the existing Android ONNX model interface:
    Input:  encoder_outputs [1, H, 1], targets [1, 1], target_length [1],
            input_states_1 [2, 1, 640], input_states_2 [2, 1, 640]
    Output: outputs [1, 1, 1, total_logits], prednet_lengths [1],
            output_states_1 [2, 1, 640], output_states_2 [2, 1, 640]
    """
    def __init__(self, decoder, joint, vocab_size, num_durations):
        super().__init__()
        self.decoder = decoder
        self.joint = joint
        self.vocab_size = vocab_size
        self.num_tokens = vocab_size + 1  # +1 for blank
        self.total_logits = vocab_size + 1 + num_durations

    def forward(self, encoder_outputs, targets, target_length,
                input_states_1, input_states_2):
        # Decoder: predict next hidden state from previous token
        state = (input_states_1, input_states_2)
        decoder_output, (h_out, c_out) = self.decoder.predict(
            targets, state=state, add_sos=False, batch_size=None
        )
        prednet_lengths = target_length

        # Transpose encoder frame from [1, H, 1] to [1, 1, H] for joint
        enc_t = encoder_outputs.transpose(1, 2)  # [1, 1, 1024]

        # Joint: combine encoder + decoder outputs → logits
        combined = self.joint.joint(enc_t, decoder_output)
        # combined: [1, 1, 1, total_logits]

        return combined, prednet_lengths, h_out, c_out


def extract_vocab(model, output_dir):
    """Extract vocabulary from the NeMo model's tokenizer."""
    tokenizer = model.tokenizer
    vocab = {}
    for i in range(tokenizer.vocab_size):
        token = tokenizer.ids_to_tokens([i])[0]
        vocab[str(i)] = token

    vocab_path = output_dir / "vocab.json"
    with open(vocab_path, "w") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    print(f"  Saved vocabulary ({len(vocab)} tokens) to {vocab_path}")
    return tokenizer.vocab_size


def save_config(model, output_dir):
    """Save model configuration for Android."""
    vocab_size = model.tokenizer.vocab_size
    durations = list(model.cfg.model_defaults.tdt_durations)
    config = {
        "numMelBins": 128,
        "sampleRate": 16000,
        "nFFT": 512,
        "hopLength": 160,
        "winLength": 400,
        "preEmphasis": 0.97,
        "encoderHidden": 1024,
        "decoderHidden": 640,
        "decoderLayers": 2,
        "vocabSize": vocab_size,
        "blankTokenId": vocab_size,
        "numDurationBins": len(durations),
        "durationBins": durations,
    }

    config_path = output_dir / "config.json"
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)
    print(f"  Config: vocabSize={vocab_size}, blank={vocab_size}, durations={durations}")


def export_encoder(model, output_dir):
    """Export encoder to ONNX."""
    print("\nExporting encoder...")
    encoder = EncoderWrapper(model.encoder)
    encoder.eval()

    # Get mel features for tracing
    preprocessor = model.preprocessor
    example_audio = torch.randn(1, 16000 * 3)  # 3 seconds
    length = torch.tensor([16000 * 3], dtype=torch.long)
    with torch.no_grad():
        mel, mel_len = preprocessor(input_signal=example_audio, length=length)

    encoder_path = output_dir / "parakeet-encoder.onnx"
    with torch.no_grad():
        torch.onnx.export(
            encoder,
            (mel, mel_len),
            str(encoder_path),
            input_names=["audio_signal", "length"],
            output_names=["outputs", "encoded_lengths"],
            dynamic_axes={
                "audio_signal": {2: "audio_signal_dynamic_axes_2"},
                "outputs": {2: "Transposeoutputs_dim_2"},
            },
            opset_version=18,
        )
    print(f"  Saved {encoder_path} ({encoder_path.stat().st_size / 1024 / 1024:.1f} MB)")
    return encoder_path


def export_decoder_joint(model, output_dir):
    """Export fused decoder+joint to ONNX."""
    print("\nExporting decoder_joint...")
    vocab_size = model.tokenizer.vocab_size
    durations = list(model.cfg.model_defaults.tdt_durations)

    wrapper = DecoderJointWrapper(
        model.decoder, model.joint,
        vocab_size=vocab_size,
        num_durations=len(durations)
    )
    wrapper.eval()

    # Example inputs matching Android interface
    encoder_outputs = torch.randn(1, 1024, 1)  # [1, H, 1]
    targets = torch.tensor([[vocab_size]], dtype=torch.long)  # blank token
    target_length = torch.tensor([1], dtype=torch.long)
    h = torch.zeros(2, 1, 640)
    c = torch.zeros(2, 1, 640)

    decoder_path = output_dir / "parakeet-decoder-joint.onnx"
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (encoder_outputs, targets, target_length, h, c),
            str(decoder_path),
            input_names=["encoder_outputs", "targets", "target_length",
                         "input_states_1", "input_states_2"],
            output_names=["outputs", "prednet_lengths",
                          "output_states_1", "output_states_2"],
            opset_version=18,
        )
    print(f"  Saved {decoder_path} ({decoder_path.stat().st_size / 1024 / 1024:.1f} MB)")
    return decoder_path


def quantize_model(model_path, output_path):
    """Quantize ONNX model with dynamic INT8 (MatMul/Gemm only, no Conv)."""
    from onnxruntime.quantization import quantize_dynamic, QuantType

    print(f"  Quantizing {model_path.name} → {output_path.name}...")
    quantize_dynamic(
        str(model_path),
        str(output_path),
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul", "Gemm"],
    )
    print(f"  Saved {output_path} ({output_path.stat().st_size / 1024 / 1024:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(description="Convert Parakeet-TDT v3 to ONNX")
    parser.add_argument("--output-dir", type=str, default="./parakeet-onnx")
    parser.add_argument("--quantize", action="store_true", help="Apply INT8 quantization")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    print("Loading NeMo model (nvidia/parakeet-tdt-0.6b-v3)...")
    model = load_nemo_model()

    vocab_size = extract_vocab(model, output_dir)
    save_config(model, output_dir)

    enc_path = export_encoder(model, output_dir)
    dec_path = export_decoder_joint(model, output_dir)

    # Free model
    del model
    gc.collect()
    torch.cuda.empty_cache() if torch.cuda.is_available() else None

    if args.quantize:
        print("\nQuantizing models (INT8, MatMul/Gemm only)...")
        enc_int8 = output_dir / "parakeet-encoder-int8.onnx"
        dec_int8 = output_dir / "parakeet-decoder-joint-int8.onnx"
        quantize_model(enc_path, enc_int8)
        quantize_model(dec_path, dec_int8)

    print(f"\nDone! Files in {output_dir}/:")
    for f in sorted(output_dir.iterdir()):
        if f.is_file():
            print(f"  {f.name}: {f.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    main()
