"""Inspect Android SAM 3 ONNX candidates against a known Web result.

This is a diagnostic for mobile post-processing calibration, not a production
dependency. It uses the same fixed-size RGB normalization as the Android app.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]


def sigmoid(value: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-value))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("reference_mask", type=Path)
    parser.add_argument(
        "--model",
        type=Path,
        default=ROOT / "android-probe/models/sam3-miniature-1008.onnx",
    )
    args = parser.parse_args()

    image = Image.open(args.image).convert("RGB").resize((1008, 1008), Image.Resampling.BILINEAR)
    pixels = np.asarray(image, dtype=np.float32) / 127.5 - 1.0
    pixels = np.transpose(pixels, (2, 0, 1))[None]

    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = 6
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(args.model, sess_options=options, providers=["CPUExecutionProvider"])
    pred_masks, _, pred_logits, presence_logits = session.run(None, {"pixel_values": pixels})

    probability_masks = sigmoid(pred_masks[0])
    binary_masks = probability_masks > 0.45
    scores = sigmoid(pred_logits[0]) * float(sigmoid(presence_logits[0, 0]))
    areas = binary_masks.reshape(len(binary_masks), -1).mean(axis=1)
    ranks = scores * np.sqrt(np.maximum(areas, 1e-8))

    reference = Image.open(args.reference_mask).convert("L").resize((288, 288), Image.Resampling.BILINEAR)
    reference = np.asarray(reference, dtype=np.float32) > 127.5
    intersection = np.logical_and(binary_masks, reference).sum(axis=(1, 2))
    union = np.logical_or(binary_masks, reference).sum(axis=(1, 2))
    iou = intersection / np.maximum(union, 1)

    print(f"presence={float(sigmoid(presence_logits[0, 0])):.6f}")
    print("query score area rank ref_iou")
    interesting = set(np.argsort(scores)[-12:])
    interesting.update(np.argsort(ranks)[-12:])
    interesting.update(np.argsort(iou)[-12:])
    for index in sorted(interesting, key=lambda item: iou[item], reverse=True):
        print(f"{index:3d} {scores[index]:.6f} {areas[index]:.6f} "
              f"{ranks[index]:.6f} {iou[index]:.6f}")

    best = int(np.argmax(iou))
    output = Path("/tmp/nmm-sam-best-candidate.png")
    Image.fromarray(binary_masks[best].astype(np.uint8) * 255).save(output)
    print(f"best_reference_query={best} saved={output}")


if __name__ == "__main__":
    main()
