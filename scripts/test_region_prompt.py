from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.pipeline import LocalNMMPipeline  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="Smoke-test SAM 3 positive-box prompting")
    parser.add_argument("image", type=Path)
    parser.add_argument("--prompt", default="circle")
    parser.add_argument(
        "--box",
        nargs=4,
        type=float,
        default=[0.05, 0.05, 0.95, 0.95],
        metavar=("X1", "Y1", "X2", "Y2"),
    )
    parser.add_argument("--compare-auto", action="store_true")
    args = parser.parse_args()

    image = Image.open(args.image).convert("RGB")
    pipeline = LocalNMMPipeline()
    auto_mask = pipeline.segment(image, args.prompt) if args.compare_auto else None
    mask = pipeline.segment(image, args.prompt, [args.box])
    coverage = float((mask > 0.1).mean())
    if not 0.001 < coverage < 0.95:
        raise RuntimeError(f"Unexpected region mask coverage: {coverage:.4f}")
    message = f"SAM 3 region prompt passed; mask coverage={coverage:.4f}"
    if auto_mask is not None:
        x1, y1, x2, y2 = args.box
        left, top = int(x1 * image.width), int(y1 * image.height)
        right, bottom = int(x2 * image.width), int(y2 * image.height)
        added = (mask > 0.1) & ~(auto_mask > 0.1)
        box_added = int(added[top:bottom, left:right].sum())
        message += f"; added pixels in box={box_added}"
    print(message)


if __name__ == "__main__":
    main()
