"""Load both local models and run a tiny end-to-end hardware smoke test."""

from __future__ import annotations

import gc
import os
from pathlib import Path

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
os.environ.setdefault("MPLCONFIGDIR", "/tmp/nmm-sim-matplotlib")

import numpy as np
import torch
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
device = "mps" if torch.backends.mps.is_available() else "cpu"
print(f"device={device}")

image = Image.new("RGB", (256, 256), "#d8d3c8")
draw = ImageDraw.Draw(image)
draw.ellipse((62, 30, 194, 162), fill="#514c46")
draw.rectangle((83, 130, 173, 235), fill="#373b36")

from transformers import Sam3Model, Sam3Processor

sam_path = ROOT / ".models" / "sam3"
processor = Sam3Processor.from_pretrained(sam_path, local_files_only=True)
sam = Sam3Model.from_pretrained(sam_path, local_files_only=True).to(device).eval()
inputs = processor(images=image, text="figure", return_tensors="pt").to(device)
with torch.inference_mode():
    outputs = sam(**inputs)
result = processor.post_process_instance_segmentation(
    outputs,
    threshold=.1,
    mask_threshold=.4,
    target_sizes=inputs["original_sizes"].tolist(),
)[0]
print(f"sam3_masks={len(result['masks'])}")
del sam, processor, inputs, outputs, result
gc.collect()
if device == "mps":
    torch.mps.empty_cache()

from depth_anything_3.api import DepthAnything3

da3_path = ROOT / ".models" / "da3-large-1.1"
da3 = DepthAnything3.from_pretrained(str(da3_path)).to(device=device).eval()
with torch.inference_mode():
    prediction = da3.inference([np.asarray(image)])
print(f"da3_depth_shape={prediction.depth.shape}")
print("smoke_test=ok")
