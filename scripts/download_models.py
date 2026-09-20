"""Download the approved model snapshots from ModelScope.

The script is resumable: ModelScope skips files that are already complete.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from modelscope import snapshot_download


ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ROOT / ".models"
MODELS = {
    "sam3": ("facebook/sam3", MODEL_ROOT / "sam3"),
    "da3": ("depth-anything/DA3-LARGE-1.1", MODEL_ROOT / "da3-large-1.1"),
}


def download(name: str) -> Path:
    model_id, destination = MODELS[name]
    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {model_id} -> {destination}")
    path = snapshot_download(model_id, local_dir=str(destination))
    print(f"Ready: {path}")
    return Path(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "models",
        nargs="*",
        choices=MODELS,
        default=list(MODELS),
        help="Models to download (default: all)",
    )
    args = parser.parse_args()
    for name in args.models:
        download(name)


if __name__ == "__main__":
    main()
