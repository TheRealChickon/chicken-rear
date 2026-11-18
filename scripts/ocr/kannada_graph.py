"""Export the weight-free ONNX wrapper for the pinned official Kannada PP-OCRv3 model.

Extract inference.json and inference.pdiparams from the official tar to a directory, then
run this script with that directory. Requires paddlepaddle 3.2.2, paddle2onnx 2.0.1 and
onnx 1.17.0. Keeps the original Paddle weights external to the APK.
"""
import subprocess
import sys
from pathlib import Path

import onnx
from onnx.external_data_helper import set_external_data


def main(directory):
    exported = directory / "local.onnx"
    subprocess.run(["paddle2onnx", "--model_dir", str(directory), "--model_filename", "inference.json",
                    "--params_filename", "inference.pdiparams", "--opset_version", "14",
                    "--save_file", str(exported)], check=True)
    graph = onnx.load(exported)
    weights = (directory / "inference.pdiparams").read_bytes()
    for node in list(graph.graph.node):
        if node.op_type != "Constant":
            continue
        tensor = node.attribute[0].t
        data = tensor.raw_data
        if not data or tensor.data_type != onnx.TensorProto.FLOAT:
            continue
        offset = weights.find(data)
        if offset < 0:
            # Converter-generated scalars, including epsilon. Never embed a weight matrix.
            assert len(data) <= 256
            continue
        initializer = graph.graph.initializer.add()
        initializer.CopyFrom(tensor)
        initializer.name = node.output[0]
        set_external_data(initializer, location="kannada.pdiparams", offset=offset, length=len(data))
        initializer.ClearField("raw_data")
        graph.graph.node.remove(node)
    target = Path(__file__).resolve().parents[2] / "app/src/main/assets/circle-ocr/kannada-graph.bin"
    target.write_bytes(graph.SerializeToString())


if __name__ == "__main__":
    main(Path(sys.argv[1]))
