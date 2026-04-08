"""
Convert a single ONNX model to TFLite format.

Usage:
    python convert_single.py --input model.onnx --output model.tflite [--quantize int8|fp16|none]

Prerequisites:
    pip install onnx onnx-tf tensorflow
"""

import argparse
import os
import sys
import tempfile
import shutil

def convert_onnx_to_tflite(input_path, output_path, quantize="none", external_data=None):
    import onnx
    import tensorflow as tf
    from onnx_tf.backend import prepare

    print(f"  Loading ONNX model: {input_path}")

    # Load ONNX model
    if external_data:
        # Model with external weights — load from directory
        model_dir = os.path.dirname(input_path)
        model = onnx.load(input_path, load_external_data=True)
    else:
        model = onnx.load(input_path)

    print(f"  ONNX opset: {model.opset_import[0].version}")
    print(f"  Inputs: {[i.name for i in model.graph.input]}")
    print(f"  Outputs: {[o.name for o in model.graph.output]}")

    # Convert to TensorFlow SavedModel
    saved_model_dir = tempfile.mkdtemp(prefix="onnx2tf_")
    try:
        print(f"  Converting to TensorFlow SavedModel...")
        tf_rep = prepare(model)
        tf_rep.export_graph(saved_model_dir)
        print(f"  SavedModel saved to: {saved_model_dir}")

        # Convert SavedModel to TFLite
        print(f"  Converting to TFLite (quantize={quantize})...")
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)

        # Allow TF ops for unsupported operations
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS,
        ]

        if quantize == "int8":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_types = [tf.int8]
            print("  Applied INT8 quantization")
        elif quantize == "fp16":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_types = [tf.float16]
            print("  Applied FP16 quantization")
        else:
            print("  No quantization (FP32)")

        tflite_model = converter.convert()

        # Save
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(tflite_model)

        size_mb = os.path.getsize(output_path) / (1024 * 1024)
        print(f"  Saved: {output_path} ({size_mb:.1f} MB)")

    finally:
        shutil.rmtree(saved_model_dir, ignore_errors=True)


def validate_tflite(path):
    """Quick validation that the TFLite file loads."""
    import tensorflow as tf

    print(f"  Validating: {path}")
    interpreter = tf.lite.Interpreter(model_path=path)
    interpreter.allocate_tensors()

    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    print(f"  Inputs: {[(d['name'], d['shape'].tolist(), d['dtype'].__name__) for d in inputs]}")
    print(f"  Outputs: {[(d['name'], d['shape'].tolist(), d['dtype'].__name__) for d in outputs]}")
    print(f"  Validation OK")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Convert ONNX model to TFLite")
    parser.add_argument("--input", required=True, help="Input .onnx file path")
    parser.add_argument("--output", required=True, help="Output .tflite file path")
    parser.add_argument("--quantize", default="none", choices=["int8", "fp16", "none"],
                        help="Quantization mode (default: none)")
    parser.add_argument("--external_data", default=None,
                        help="Path to external data file (e.g., .onnx.data)")
    parser.add_argument("--skip_validation", action="store_true",
                        help="Skip TFLite validation after conversion")
    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f"Error: Input file not found: {args.input}")
        sys.exit(1)

    try:
        convert_onnx_to_tflite(args.input, args.output, args.quantize, args.external_data)
        if not args.skip_validation:
            validate_tflite(args.output)
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
