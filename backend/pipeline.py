from __future__ import annotations

import gc
import os
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from .nmm_shader import depth_to_detail_normals, depth_to_normals, make_line_art, render_nmm


ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ROOT / ".models"


def best_device() -> str:
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


class LocalNMMPipeline:
    def __init__(self) -> None:
        self.device = best_device()
        self.last_depth_info: dict[str, object] = {}

    def _release(self) -> None:
        # MPS work is asynchronous. Synchronize before returning allocations to
        # Metal so the next high-resolution request cannot reuse live buffers.
        if self.device == "mps":
            torch.mps.synchronize()
        elif self.device == "cuda":
            torch.cuda.synchronize()
        gc.collect()
        if self.device == "mps":
            torch.mps.empty_cache()
        elif self.device == "cuda":
            torch.cuda.empty_cache()

    def segment(
        self,
        image: Image.Image,
        prompt: str,
        regions: list[list[float]] | None = None,
    ) -> np.ndarray:
        from transformers import Sam3Model, Sam3Processor

        model_path = MODEL_ROOT / "sam3"
        if not model_path.exists():
            raise FileNotFoundError("SAM 3 is not downloaded. Run scripts/download_models.py sam3")
        processor = Sam3Processor.from_pretrained(model_path)
        model = Sam3Model.from_pretrained(model_path).to(self.device).eval()
        def infer(box: list[float] | None = None) -> np.ndarray | None:
            kwargs: dict[str, object] = {}
            if box is not None:
                kwargs["input_boxes"] = [[box]]
            inputs = processor(
                images=image,
                # Once the user supplies a positive box, geometry is the
                # authority. A generic noun prevents detached weapons,
                # backpacks or banners from being rejected as "not a figure".
                text="object" if box is not None else prompt,
                return_tensors="pt",
                **kwargs,
            ).to(self.device)
            with torch.inference_mode():
                outputs = model(**inputs)
            result = processor.post_process_instance_segmentation(
                outputs,
                threshold=0.28 if box is not None else 0.32,
                mask_threshold=0.42 if box is not None else 0.45,
                target_sizes=inputs["original_sizes"].tolist(),
            )[0]
            masks = result["masks"].detach().float().cpu().numpy()
            scores = result["scores"].detach().float().cpu().numpy()
            if not len(masks):
                del inputs, outputs
                self._release()
                return None
            areas = np.maximum(masks.reshape(len(masks), -1).sum(axis=1), 1e-6)
            if box is None:
                relative_areas = areas / masks[0].size
                rank = scores * np.sqrt(relative_areas)
            else:
                left = max(0, min(image.width - 1, int(np.floor(box[0]))))
                top = max(0, min(image.height - 1, int(np.floor(box[1]))))
                right = max(left + 1, min(image.width, int(np.ceil(box[2]))))
                bottom = max(top + 1, min(image.height, int(np.ceil(box[3]))))
                intersections = masks[:, top:bottom, left:right].reshape(len(masks), -1).sum(axis=1)
                box_area = float((right - left) * (bottom - top))
                box_coverage = intersections / max(box_area, 1.0)
                precision = intersections / areas
                union = areas + box_area - intersections
                iou = intersections / np.maximum(union, 1e-6)
                # Prefer the candidate actually occupying the user's box,
                # instead of falling back to a larger mask elsewhere.
                rank = scores * (box_coverage * 0.58 + precision * 0.24 + iou * 0.18)
                if float(np.max(box_coverage)) < 0.01:
                    del inputs, outputs
                    self._release()
                    return None
            selected = masks[int(np.argmax(rank))].astype(np.float32)
            del inputs, outputs
            self._release()
            return selected

        mask = infer()
        if mask is None:
            mask = np.zeros((image.height, image.width), dtype=np.float32)

        matched_regions = 0
        for normalized in regions or []:
            x1, y1, x2, y2 = normalized
            box = [x1 * image.width, y1 * image.height, x2 * image.width, y2 * image.height]
            addition = infer(box)
            if addition is not None:
                mask = np.maximum(mask, addition)
                matched_regions += 1

        del model, processor
        self._release()
        if not np.any(mask > 0.1):
            raise ValueError(f"SAM 3 found no object for prompt: {prompt}")
        if regions and matched_regions == 0:
            raise ValueError("SAM 3 could not find an object in the selected supplement region")
        return mask

    def estimate_depth(
        self, image: Image.Image, process_res: int = 1008
    ) -> tuple[np.ndarray, np.ndarray | None]:
        model_path = MODEL_ROOT / "da3-large-1.1"
        if not model_path.exists():
            raise FileNotFoundError("DA3 is not downloaded. Run scripts/download_models.py da3")
        try:
            from depth_anything_3.api import DepthAnything3
        except ImportError as exc:
            raise RuntimeError("Depth Anything 3 package is not installed") from exc
        model = DepthAnything3.from_pretrained(str(model_path)).to(device=self.device)
        try:
            prediction = model.inference(
                [image],
                process_res=process_res,
                process_res_method="upper_bound_resize",
            )
        except RuntimeError:
            if self.device != "mps":
                raise
            del model
            self._release()
            model = DepthAnything3.from_pretrained(str(model_path)).to(device="cpu")
            prediction = model.inference(
                [image],
                process_res=process_res,
                process_res_method="upper_bound_resize",
            )
        depth = np.asarray(prediction.depth[0], dtype=np.float32)
        intrinsics = None
        if prediction.intrinsics is not None:
            intrinsics = np.asarray(prediction.intrinsics[0], dtype=np.float32).copy()
        del model, prediction
        self._release()
        return depth, intrinsics

    @staticmethod
    def _subject_focus_box(mask: np.ndarray) -> tuple[tuple[int, int, int, int], float]:
        """Return a context-padded subject crop and its sampling-density gain."""
        height, width = mask.shape
        yy, xx = np.nonzero(mask > 0.1)
        if not len(xx):
            return (0, 0, width, height), 1.0
        subject_left, subject_right = int(xx.min()), int(xx.max()) + 1
        subject_top, subject_bottom = int(yy.min()), int(yy.max()) + 1
        subject_width = subject_right - subject_left
        subject_height = subject_bottom - subject_top
        pad_x = max(24, int(round(subject_width * 0.14)))
        pad_y = max(24, int(round(subject_height * 0.14)))
        left = max(0, subject_left - pad_x)
        top = max(0, subject_top - pad_y)
        right = min(width, subject_right + pad_x)
        bottom = min(height, subject_bottom + pad_y)
        crop_long_edge = max(right - left, bottom - top)
        gain = max(width, height) / max(1, crop_long_edge)
        # A tiny crop only removes context without materially increasing the
        # model's sampling density. Keep the original image in that case.
        if gain < 1.08:
            return (0, 0, width, height), 1.0
        return (left, top, right, bottom), float(gain)

    @staticmethod
    def _resize_depth(
        depth: np.ndarray,
        intrinsics: np.ndarray | None,
        size: tuple[int, int],
    ) -> tuple[np.ndarray, np.ndarray | None]:
        target_width, target_height = size
        old_height, old_width = depth.shape
        if (old_width, old_height) != size:
            depth = np.asarray(
                Image.fromarray(depth).resize(size, Image.Resampling.BICUBIC),
                dtype=np.float32,
            )
            if intrinsics is not None:
                intrinsics = np.asarray(intrinsics, dtype=np.float32).copy()
                intrinsics[0, :] *= target_width / old_width
                intrinsics[1, :] *= target_height / old_height
        return depth, intrinsics

    def run(
        self,
        image: Image.Image,
        prompt: str = "miniature figure",
        regions: list[list[float]] | None = None,
        depth_resolution: int = 1008,
    ) -> dict[str, Image.Image]:
        image = image.convert("RGB")
        mask = self.segment(image, prompt, regions)
        focus_box, effective_gain = self._subject_focus_box(mask)
        left, top, right, bottom = focus_box
        focused = focus_box != (0, 0, image.width, image.height)
        depth_image = image.crop(focus_box) if focused else image
        crop_depth, intrinsics = self.estimate_depth(depth_image, depth_resolution)
        crop_depth, intrinsics = self._resize_depth(crop_depth, intrinsics, depth_image.size)
        if focused:
            fill_depth = float(np.nanmedian(crop_depth))
            depth = np.full(mask.shape, fill_depth, dtype=np.float32)
            depth[top:bottom, left:right] = crop_depth
            if intrinsics is not None:
                intrinsics[0, 2] += left
                intrinsics[1, 2] += top
        else:
            depth = crop_depth
        self.last_depth_info = {
            "mode": "subject_focus" if focused else "full_frame",
            "process_resolution": depth_resolution,
            "source_size": [image.width, image.height],
            "inference_size": [depth_image.width, depth_image.height],
            "focus_box": [left, top, right, bottom],
            "effective_gain": round(effective_gain, 2),
        }
        source = np.asarray(image)
        normal_vectors = depth_to_normals(depth, mask, intrinsics)
        lineart = make_line_art(source, mask, normal_vectors, depth)
        final, normals, guide = render_nmm(
            lineart, mask, depth, intrinsics=intrinsics, normals=normal_vectors
        )
        cutout = image.convert("RGBA")
        cutout.putalpha(Image.fromarray(np.clip(mask * 255, 0, 255).astype(np.uint8)))
        depth_valid = depth[mask > .1]
        lo, hi = np.percentile(depth_valid, [2, 98])
        depth_png = np.clip((depth - lo) / max(float(hi - lo), 1e-6) * 255, 0, 255).astype(np.uint8)
        return {
            "mask": Image.fromarray(np.clip(mask * 255, 0, 255).astype(np.uint8)),
            "cutout": cutout,
            "depth": Image.fromarray(depth_png),
            "lineart": Image.fromarray(lineart),
            "normals": Image.fromarray(normals),
            "guide": Image.fromarray(guide),
            "result": Image.fromarray(final),
        }

    def refine_region(
        self,
        image: Image.Image,
        mask_image: Image.Image,
        box: tuple[int, int, int, int],
        depth_resolution: int = 1008,
    ) -> tuple[dict[str, Image.Image], dict[str, object]]:
        """Infer one selected crop at full model resolution for a zoomed working view."""
        image = image.convert("RGB")
        mask_image = mask_image.convert("L")
        left, top, right, bottom = box
        crop = image.crop(box)
        crop_mask = mask_image.crop(box)
        depth, intrinsics = self.estimate_depth(crop, depth_resolution)
        output_size = (int(depth.shape[1]), int(depth.shape[0]))
        source_high = crop.resize(output_size, Image.Resampling.LANCZOS)
        mask_high_image = crop_mask.resize(output_size, Image.Resampling.LANCZOS)
        mask = np.asarray(mask_high_image, dtype=np.float32) / 255.0
        if np.count_nonzero(mask > 0.1) < 64:
            raise ValueError("Selected local region does not contain recognized subject")
        source = np.asarray(source_high)
        normal_vectors = depth_to_normals(depth, mask, intrinsics)
        detail_normal_vectors = depth_to_detail_normals(depth, mask, intrinsics)
        lineart = make_line_art(source, mask, normal_vectors, depth)
        final, normals, guide = render_nmm(
            lineart, mask, depth, intrinsics=intrinsics, normals=normal_vectors
        )
        cutout = source_high.convert("RGBA")
        cutout.putalpha(mask_high_image)
        depth_valid = depth[mask > 0.1]
        low, high = np.percentile(depth_valid, [2, 98])
        depth_png = np.clip(
            (depth - low) / max(float(high - low), 1e-6) * 255, 0, 255
        ).astype(np.uint8)
        crop_width, crop_height = crop.size
        info: dict[str, object] = {
            "source_box": [left, top, right, bottom],
            "source_size": [crop_width, crop_height],
            "output_size": [output_size[0], output_size[1]],
            "linear_gain": round(
                min(output_size[0] / max(1, crop_width), output_size[1] / max(1, crop_height)),
                2,
            ),
            "process_resolution": depth_resolution,
        }
        return {
            "crop": source_high,
            "cutout": cutout,
            "mask": mask_high_image,
            "depth": Image.fromarray(depth_png),
            "lineart": Image.fromarray(lineart),
            "normals": Image.fromarray(normals),
            "detail_normals": Image.fromarray(
                np.clip((detail_normal_vectors + 1.0) * 127.5, 0, 255).astype(np.uint8)
            ),
            "guide": Image.fromarray(guide),
            "result": Image.fromarray(final),
        }, info
