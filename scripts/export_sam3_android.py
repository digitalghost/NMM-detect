"""Export the current SAM 3 automatic miniature mask path for Android.

NMM-detect uses a fixed text prompt for its initial recognition pass. SAM 3
officially supports precomputed text features, so this exporter stores the
feature for ``miniature figure`` in the graph and omits the 353M-parameter text
encoder. All active vision, detector, and mask-decoder weights remain the exact
weights from the current checkpoint.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch
from torch import nn
from transformers import Sam3Model, Sam3Processor
from transformers.modeling_outputs import BaseModelOutputWithPooling


ROOT = Path(__file__).resolve().parents[1]


class AndroidSam3(nn.Module):
    def __init__(
        self,
        model: Sam3Model,
        text_features: torch.Tensor,
        attention_mask: torch.Tensor,
    ) -> None:
        super().__init__()
        self.model = model
        self.register_buffer("text_features", text_features)
        self.register_buffer("attention_mask", attention_mask)

    def forward(
        self, pixel_values: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        text = BaseModelOutputWithPooling(pooler_output=self.text_features)
        output = self.model(
            pixel_values=pixel_values,
            text_embeds=text,
            attention_mask=self.attention_mask,
        )
        return (
            output.pred_masks,
            output.pred_boxes,
            output.pred_logits,
            output.presence_logits,
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "android-probe" / "models" / "sam3-miniature-1008.onnx",
    )
    parser.add_argument("--prompt", default="miniature figure")
    parser.add_argument("--fp16", action="store_true")
    args = parser.parse_args()

    model_path = ROOT / ".models" / "sam3"
    processor = Sam3Processor.from_pretrained(model_path, local_files_only=True)
    model = Sam3Model.from_pretrained(model_path, local_files_only=True).eval()
    # ONNX lowers attention to the same MatMul/Softmax primitives. Selecting
    # the eager interface here avoids a PyTorch exporter stride bug in the
    # SDPA cross-attention result; it does not change any learned parameter.
    model.set_attn_implementation("eager")
    text_inputs = processor(text=args.prompt, return_tensors="pt")
    with torch.no_grad():
        text_features = model.get_text_features(
            input_ids=text_inputs.input_ids,
            attention_mask=text_inputs.attention_mask,
        ).pooler_output.detach().clone()

    # The feature above is the complete output of the official text path.
    # Deleting the encoder removes only code and weights no longer reachable.
    model.text_encoder = None
    dtype = torch.float16 if args.fp16 else torch.float32
    if args.fp16:
        model = model.half()
        text_features = text_features.half()

    wrapper = AndroidSam3(
        model,
        text_features,
        text_inputs.attention_mask,
    ).eval()
    example = torch.zeros((1, 3, 1008, 1008), dtype=dtype)

    print(
        f"Capturing SAM 3 graph 1008x1008, prompt={args.prompt!r}, dtype={dtype}",
        flush=True,
    )
    exported = torch.export.export(wrapper, (example,), strict=False)
    print(f"Captured {len(list(exported.graph.nodes))} graph nodes", flush=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        exported,
        (),
        args.output,
        input_names=["pixel_values"],
        output_names=["pred_masks", "pred_boxes", "pred_logits", "presence_logits"],
        opset_version=18,
        dynamo=True,
        external_data=True,
    )
    print(f"Exported {args.output}", flush=True)


if __name__ == "__main__":
    main()
