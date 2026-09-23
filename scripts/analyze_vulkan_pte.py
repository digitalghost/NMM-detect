"""Summarize Vulkan delegate memory encoded in an ExecuTorch PTE file.

This is a diagnostic helper for the Android DA3 probe.  It reports the raw
tensor footprint of every Vulkan delegate and groups transient tensors by
``mem_obj_id`` because tensors sharing that id reuse the same allocation.
The estimate deliberately excludes driver-specific staging and image padding;
it is therefore a lower bound, but it makes unexpectedly large delegate graphs
easy to identify before installing them on a device.
"""

from __future__ import annotations

import argparse
import math
from collections import Counter, defaultdict
from pathlib import Path

from executorch.backends.vulkan.serialization.vulkan_graph_schema import (
    VkDataType,
    VkTensor,
)
from executorch.backends.vulkan.serialization.vulkan_graph_serialize import (
    extract_vk_flatbuffer,
    flatbuffer_to_vk_graph,
)
from executorch.exir._serialize._program import deserialize_pte_binary


DTYPE_BYTES = {
    VkDataType.BOOL: 1,
    VkDataType.UINT8: 1,
    VkDataType.INT8: 1,
    VkDataType.INT32: 4,
    VkDataType.FLOAT16: 2,
    VkDataType.FLOAT32: 4,
    VkDataType.FLOAT64: 8,
    VkDataType.INT64: 8,
}


def tensor_bytes(tensor: VkTensor) -> int:
    return math.prod(tensor.dims) * DTYPE_BYTES[VkDataType(tensor.datatype)]


def mib(value: int) -> str:
    return f"{value / 1024**2:.1f} MiB"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pte", type=Path)
    parser.add_argument("--top", type=int, default=12)
    args = parser.parse_args()

    pte = deserialize_pte_binary(args.pte.read_bytes())
    program = pte.program
    plan = program.execution_plan[0]

    rows: list[tuple[int, int, int, int, int, str]] = []
    for index, delegate in enumerate(plan.delegates):
        processed = program.backend_delegate_data[delegate.processed.index].data
        graph = flatbuffer_to_vk_graph(extract_vk_flatbuffer(processed))
        tensors = [
            value.value for value in graph.values if isinstance(value.value, VkTensor)
        ]

        transient_by_object: dict[int, list[int]] = defaultdict(list)
        constant_bytes = 0
        for tensor in tensors:
            size = tensor_bytes(tensor)
            if tensor.constant_id >= 0:
                constant_bytes += size
            elif tensor.mem_obj_id >= 0:
                transient_by_object[tensor.mem_obj_id].append(size)

        reusable_bytes = sum(max(sizes) for sizes in transient_by_object.values())
        raw_transient_bytes = sum(
            tensor_bytes(tensor)
            for tensor in tensors
            if tensor.constant_id < 0
        )
        storage = Counter(str(tensor.storage_type) for tensor in tensors)
        rows.append(
            (
                index,
                reusable_bytes,
                raw_transient_bytes,
                constant_bytes,
                len(tensors),
                ", ".join(f"{name}:{count}" for name, count in storage.items()),
            )
        )

    print(f"PTE: {args.pte}")
    print(f"Delegates: {len(rows)}")
    print(f"ExecuTorch non-constant arena: {mib(sum(plan.non_const_buffer_sizes))}")
    print(
        "Vulkan reusable tensor lower bound: "
        + mib(sum(row[1] for row in rows))
    )
    print(
        "Vulkan raw transient tensors (without reuse): "
        + mib(sum(row[2] for row in rows))
    )
    print(f"Vulkan constants referenced by graphs: {mib(sum(row[3] for row in rows))}")
    print()
    print("Largest delegates by reusable tensor lower bound:")
    for index, reusable, raw, constants, tensors, storage in sorted(
        rows, key=lambda row: row[1], reverse=True
    )[: args.top]:
        print(
            f"  #{index:02d} reusable={mib(reusable):>11} "
            f"raw={mib(raw):>11} constants={mib(constants):>11} "
            f"tensors={tensors:4d} storage=[{storage}]"
        )


if __name__ == "__main__":
    main()
