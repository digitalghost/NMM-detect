"""Export the exact DA3-LARGE-1.1 depth path for the Android probe.

The upstream RoPE implementation derives a Python integer from tensor data.
That is harmless during eager inference, but prevents a static mobile graph
from being captured. The Android probe uses fixed input buckets, so the
maximum position is known from the selected height and width. Replacing that
one data-dependent integer with the equivalent constant does not change the
model math or any learned weight.
"""

from __future__ import annotations

import argparse
import types
from pathlib import Path

import torch
from torch import nn

from depth_anything_3.model.dinov2.layers.rope import RotaryPositionEmbedding2D


ROOT = Path(__file__).resolve().parents[1]
PATCH_SIZE = 14


class AndroidDA3(nn.Module):
    """Only return the two values consumed by NMM-detect."""

    def __init__(self, network: nn.Module) -> None:
        super().__init__()
        self.network = network

    def forward(self, image: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        output = self.network(
            image,
            None,
            None,
            [],
            False,
            False,
            "saddle_balanced",
        )
        return output.depth, output.intrinsics


def install_static_rope(network: nn.Module, height: int, width: int) -> int:
    max_position = max(height // PATCH_SIZE, width // PATCH_SIZE) + 1

    def fixed_forward(
        self: RotaryPositionEmbedding2D,
        tokens: torch.Tensor,
        positions: torch.Tensor,
    ) -> torch.Tensor:
        feature_dim = tokens.size(-1) // 2
        cos_comp, sin_comp = self._compute_frequency_components(
            feature_dim,
            max_position,
            tokens.device,
            tokens.dtype,
        )
        vertical, horizontal = tokens.chunk(2, dim=-1)
        vertical = self._apply_1d_rope(
            vertical, positions[..., 0], cos_comp, sin_comp
        )
        horizontal = self._apply_1d_rope(
            horizontal, positions[..., 1], cos_comp, sin_comp
        )
        return torch.cat((vertical, horizontal), dim=-1)

    patched = 0
    for module in network.modules():
        if isinstance(module, RotaryPositionEmbedding2D):
            module.forward = types.MethodType(fixed_forward, module)
            patched += 1
    return patched


def main() -> None:
    from depth_anything_3.api import DepthAnything3

    parser = argparse.ArgumentParser()
    parser.add_argument("--height", type=int, default=1008)
    parser.add_argument("--width", type=int, default=756)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "android-probe" / "models" / "da3-large-1008x756.onnx",
    )
    parser.add_argument(
        "--fp16",
        action="store_true",
        help="Store and execute active weights as FP16. FP32 is the parity baseline.",
    )
    args = parser.parse_args()

    if args.height % PATCH_SIZE or args.width % PATCH_SIZE:
        raise SystemExit("Height and width must both be divisible by 14")

    loaded = DepthAnything3.from_pretrained(
        str(ROOT / ".models" / "da3-large-1.1")
    )
    network = loaded.model.eval()
    # The desktop path never supplies external camera parameters, so this
    # module is unreachable and can be omitted without touching active weights.
    network.cam_enc = None
    patched = install_static_rope(network, args.height, args.width)
    if not patched:
        raise RuntimeError("No DA3 rotary embedding modules were found")

    dtype = torch.float16 if args.fp16 else torch.float32
    if args.fp16:
        network = network.half()
    wrapper = AndroidDA3(network).eval()
    example = torch.zeros((1, 1, 3, args.height, args.width), dtype=dtype)

    print(
        f"Capturing DA3 graph {args.height}x{args.width}, "
        f"dtype={dtype}, patched_rope={patched}",
        flush=True,
    )
    exported = torch.export.export(wrapper, (example,), strict=False)
    print(f"Captured {len(list(exported.graph.nodes))} graph nodes", flush=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        exported,
        (),
        args.output,
        input_names=["image"],
        output_names=["depth", "intrinsics"],
        opset_version=18,
        dynamo=True,
        external_data=True,
    )
    print(f"Exported {args.output}", flush=True)


if __name__ == "__main__":
    main()
