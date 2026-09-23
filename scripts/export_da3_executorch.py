"""Export DA3-LARGE-1.1 for the ExecuTorch Vulkan Android probe.

The model weights and fixed 1008x756 inference path are the same as the
desktop application.  ExecuTorch's Vulkan partitioner decides which graph
regions can run on the Android GPU; unsupported regions remain portable CPU
operators.  The script prints the resulting delegate layout so that a build
which silently falls back to CPU is not mistaken for a successful GPU port.
"""

from __future__ import annotations

import argparse
import logging
import math
import sys
import types
from pathlib import Path

import torch
from executorch.backends.vulkan.partitioner.vulkan_partitioner import (
    VulkanPartitioner,
)
from executorch.exir import EdgeCompileConfig, to_edge
from executorch.exir.backend.partitioner import PartitionResult
from executorch.exir.dialects._ops import ops as exir_ops

from export_da3_android import AndroidDA3, ROOT, install_static_rope


class SharedConstantVulkanPartitioner(VulkanPartitioner):
    """Keep constants shared when several Vulkan regions consume them.

    ExecuTorch normally duplicates a lifted constant once per delegate region.
    DA3's learned positional embedding is relatively large and is consumed by
    more than one region.  Passing that tensor across delegate boundaries keeps
    one authoritative weight and also avoids an upstream duplicate-placeholder
    FakeTensorMode issue.
    """

    def partition(self, exported_program: torch.export.ExportedProgram) -> PartitionResult:
        result = super().partition(exported_program)
        shared: list[str] = []
        for node in result.tagged_exported_program.graph.nodes:
            if node.op != "placeholder":
                continue
            tag = node.meta.get("delegation_tag")
            if tag is None:
                continue
            user_tags = {
                user.meta.get("delegation_tag") for user in node.users
            }
            if any(user_tag != tag for user_tag in user_tags):
                node.meta.pop("delegation_tag", None)
                shared.append(node.name)

        if shared:
            print("Shared cross-region constants: " + ", ".join(shared))
        used_tags = {
            node.meta.get("delegation_tag")
            for node in result.tagged_exported_program.graph.nodes
            if node.meta.get("delegation_tag") is not None
        }
        result.partition_tags = {
            tag: spec
            for tag, spec in result.partition_tags.items()
            if tag in used_tags
        }
        return result


def _delegate_summary(edge_program: object) -> tuple[int, int, list[str]]:
    """Return delegate calls, remaining call ops, and their operator names."""
    exported = edge_program.exported_program()
    delegate_calls = 0
    portable_calls = 0
    portable_ops: set[str] = set()

    for node in exported.graph.nodes:
        if node.op != "call_function":
            continue
        target = str(node.target)
        if "executorch_call_delegate" in target:
            delegate_calls += 1
        else:
            portable_calls += 1
            portable_ops.add(target)

    return delegate_calls, portable_calls, sorted(portable_ops)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--height", type=int, default=1008)
    parser.add_argument("--width", type=int, default=756)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "android-probe" / "models" / "da3-large-vulkan.pte",
    )
    parser.add_argument(
        "--fp16",
        action="store_true",
        help=(
            "Keep the captured DA3 graph in FP32, but ask the Vulkan compiler "
            "to store and execute delegated tensors in FP16."
        ),
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Export a tiny convolution graph to validate the toolchain only.",
    )
    args = parser.parse_args()

    # The partitioner logs a reason for every skipped operator at INFO level.
    logging.basicConfig(level=logging.INFO)

    if args.smoke:
        model: torch.nn.Module = torch.nn.Sequential(
            torch.nn.Conv2d(3, 8, 3, padding=1),
            torch.nn.GELU(),
            torch.nn.Conv2d(8, 1, 1),
        ).eval()
        example = torch.zeros((1, 3, 64, 64), dtype=torch.float32)
    else:
        if args.height % 14 or args.width % 14:
            raise SystemExit("Height and width must both be divisible by 14")

        # Imported lazily so --smoke does not initialize the large model stack.
        # DA3's public API imports optional COLMAP exporters eagerly.  The
        # depth-only graph never calls them; avoiding the native pycolmap
        # module also prevents a duplicate OpenMP runtime in the exporter.
        sys.modules.setdefault("pycolmap", types.ModuleType("pycolmap"))
        from depth_anything_3.api import DepthAnything3

        loaded = DepthAnything3.from_pretrained(
            str(ROOT / ".models" / "da3-large-1.1")
        )
        network = loaded.model.eval()
        # The application never supplies camera parameters, so this inactive
        # branch is omitted without changing any executed learned weight.
        network.cam_enc = None
        patched = install_static_rope(network, args.height, args.width)
        if not patched:
            raise RuntimeError("No DA3 rotary embedding modules found")

        model = AndroidDA3(network).eval()
        # DA3's image normalization intentionally produces FP32.  Capturing a
        # half-typed module would therefore mismatch the first convolution.
        # Vulkan's force_fp16 option performs the safe delegate-boundary and
        # constant conversion after capture instead.
        example = torch.zeros(
            (1, 1, 3, args.height, args.width), dtype=torch.float32
        )

    print(f"Capturing graph: shape={tuple(example.shape)}, dtype={example.dtype}")
    # DA3 contains Python constructs which require non-strict capture.  The
    # resulting pure FX module is then strict-exported a second time so every
    # tensor uses the single FakeTensorMode expected by ExecuTorch 1.4 passes.
    first_export = torch.export.export(model, (example,), strict=False)
    exported = torch.export.export(first_export.module(), (example,), strict=True)
    if exported.range_constraints:
        raise RuntimeError("Android probe requires a fully static export")

    # ExecuTorch refreshes symbolic range constraints after every backend
    # pass.  This fixed-shape graph has no symbolic ranges, but that refresh
    # still asks PyTorch to reconcile FakeTensorModes from delegate boundary
    # placeholders.  PyTorch rejects those otherwise harmless mixed modes.
    # Returning the known-empty constraints avoids only that dynamic-shape
    # bookkeeping; it does not alter graph values or operator lowering.
    import executorch.exir.pass_manager as exir_pass_manager

    exir_pass_manager._get_updated_range_constraints = lambda _graph: {}

    # flatc does not accept Python JSON's Infinity/-Infinity tokens.  DA3 uses
    # negative infinity only as an attention-mask sentinel.  Replacing it with
    # the most-negative finite FP32 value is softmax-equivalent (exp underflows
    # to zero) and becomes -inf again when the Vulkan delegate casts to FP16.
    nonfinite_scalars = 0

    def sanitize_scalar(value: object) -> object:
        nonlocal nonfinite_scalars
        if isinstance(value, float):
            if math.isnan(value):
                raise RuntimeError("Unexpected NaN scalar in exported DA3 graph")
            if math.isinf(value):
                nonfinite_scalars += 1
                limit = torch.finfo(torch.float32).max
                return math.copysign(limit, value)
        return value

    for node in exported.graph.nodes:
        node.args = torch.fx.node.map_aggregate(node.args, sanitize_scalar)
        node.kwargs = torch.fx.node.map_aggregate(node.kwargs, sanitize_scalar)
    if nonfinite_scalars:
        print(f"Sanitized non-finite mask scalars: {nonfinite_scalars}")
        exported.graph_module.recompile()
    captured_calls = sum(
        node.op == "call_function" for node in exported.graph.nodes
    )
    print(f"Captured call_function nodes: {captured_calls}")

    edge_program = to_edge(
        exported,
        compile_config=EdgeCompileConfig(_skip_dim_order=False),
    )
    # Attention decomposition happens while converting ATen to Edge, so scan
    # once more for mask sentinels introduced by that transformation.
    edge_exported = edge_program.exported_program()
    edge_nonfinite_before = nonfinite_scalars
    for node in edge_exported.graph.nodes:
        node.args = torch.fx.node.map_aggregate(node.args, sanitize_scalar)
        node.kwargs = torch.fx.node.map_aggregate(node.kwargs, sanitize_scalar)
    if nonfinite_scalars != edge_nonfinite_before:
        print(
            "Sanitized Edge mask scalars: "
            f"{nonfinite_scalars - edge_nonfinite_before}"
        )
        edge_exported.graph_module.recompile()

    # The 1.4 Python Vulkan registry accepts these scalar operators, but the
    # official Android Vulkan AAR does not register their runtime kernels.
    # Rewrite them to equivalent operators which are present in the AAR.
    logical_not_rewrites = 0
    scalar_lt_rewrites = 0
    edge_graph = edge_exported.graph
    for node in list(edge_graph.nodes):
        if node.op != "call_function":
            continue
        if node.target == exir_ops.edge.aten.logical_not.default:
            node.target = exir_ops.edge.aten.eq.Scalar
            node.args = (node.args[0], False)
            node.kwargs = {}
            logical_not_rewrites += 1
        elif node.target == exir_ops.edge.aten.lt.Scalar:
            tensor, scalar = node.args[:2]
            with edge_graph.inserting_before(node):
                scalar_tensor = edge_graph.call_function(
                    exir_ops.edge.aten.full_like.default,
                    args=(tensor, scalar),
                )
            scalar_tensor.meta = dict(tensor.meta)
            scalar_tensor.meta["val"] = torch.full_like(
                tensor.meta["val"], scalar
            )
            node.target = exir_ops.edge.aten.lt.Tensor
            node.args = (tensor, scalar_tensor)
            node.kwargs = {}
            scalar_lt_rewrites += 1
    if logical_not_rewrites or scalar_lt_rewrites:
        print(
            "Android Vulkan compatibility rewrites: "
            f"logical_not={logical_not_rewrites}, "
            f"lt_scalar={scalar_lt_rewrites}"
        )
        edge_graph.lint()
        edge_exported.graph_module.recompile()
    edge_program = edge_program.to_backend(
        SharedConstantVulkanPartitioner({"force_fp16": args.fp16})
    )
    delegates, portable, portable_ops = _delegate_summary(edge_program)
    print(f"Vulkan delegate regions: {delegates}")
    print(f"Portable call_function nodes after lowering: {portable}")
    if portable_ops:
        print("Portable operators:")
        for name in portable_ops:
            print(f"  {name}")

    program = edge_program.to_executorch()
    runtime_delegates = program.executorch_program.execution_plan[0].delegates
    print(
        "Runtime delegates: "
        + (", ".join(delegate.id for delegate in runtime_delegates) or "none")
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as output_file:
        program.write_to_file(output_file)
    print(f"Wrote {args.output} ({args.output.stat().st_size / 1024**2:.1f} MiB)")


if __name__ == "__main__":
    main()
