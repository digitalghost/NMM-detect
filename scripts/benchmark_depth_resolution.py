"""Measure one DA3 inference resolution in a fresh process."""

from __future__ import annotations

import argparse
import gc
import time
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from depth_anything_3.api import DepthAnything3


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--resolution", type=int, required=True)
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    image = Image.open(args.image).convert("RGB")
    model = DepthAnything3.from_pretrained(
        str(ROOT / ".models" / "da3-large-1.1")
    ).to(device=device).eval()

    started = time.perf_counter()
    with torch.inference_mode():
        prediction = model.inference(
            [np.asarray(image)],
            process_res=args.resolution,
            process_res_method="upper_bound_resize",
        )
    if device == "mps":
        torch.mps.synchronize()
    elapsed = time.perf_counter() - started
    print(
        f"resolution={args.resolution} depth_shape={prediction.depth.shape} "
        f"seconds={elapsed:.2f}",
        flush=True,
    )

    del prediction, model
    gc.collect()
    if device == "mps":
        torch.mps.synchronize()
        torch.mps.empty_cache()


if __name__ == "__main__":
    main()
