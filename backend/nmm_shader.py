from __future__ import annotations

import numpy as np
import cv2
from scipy.ndimage import binary_erosion, gaussian_filter


PALETTES = {
    "silver": np.array([[8, 13, 19], [39, 51, 64], [106, 123, 142], [205, 217, 226], [255, 255, 248]], dtype=np.float32),
    "gold": np.array([[24, 13, 5], [75, 42, 9], [153, 90, 18], [235, 176, 58], [255, 246, 180]], dtype=np.float32),
    "bronze": np.array([[24, 12, 10], [75, 35, 24], [139, 72, 43], [218, 139, 83], [255, 226, 176]], dtype=np.float32),
}


def normalize_depth(depth: np.ndarray, mask: np.ndarray) -> np.ndarray:
    depth = np.asarray(depth, dtype=np.float32)
    valid = depth[mask > 0.1]
    if valid.size < 10:
        raise ValueError("The detected subject mask is empty or too small")
    low, high = np.percentile(valid, [2, 98])
    scaled = np.clip((depth - low) / max(float(high - low), 1e-6), 0, 1)
    return gaussian_filter(scaled, sigma=1.15)


def depth_to_normals(
    depth: np.ndarray,
    mask: np.ndarray,
    intrinsics: np.ndarray | None = None,
) -> np.ndarray:
    """Recover a camera-facing normal for every depth-derived 3D point."""
    depth = np.asarray(depth, dtype=np.float32)
    mask = np.asarray(mask, dtype=np.float32)
    valid = (mask > 0.1) & np.isfinite(depth) & (depth > 1e-6)
    if np.count_nonzero(valid) < 10:
        raise ValueError("The detected subject mask is empty or too small")

    # Multi-scale normalized smoothing avoids mixing in the background while
    # suppressing the high-frequency depth noise that derivatives amplify.
    # Preserve the extra spatial detail produced by 1008 px DA3 inference.
    # A light multi-scale regularization still suppresses derivative noise, but
    # no longer turns small armour plates into one broad normal patch.
    geometry_sigma = float(np.clip(max(depth.shape) / 420.0, 1.15, 3.4))
    valid_float = valid.astype(np.float32)

    def masked_smooth(sigma: float) -> np.ndarray:
        weights = gaussian_filter(valid_float, sigma=sigma)
        values = gaussian_filter(np.where(valid, depth, 0.0), sigma=sigma)
        return values / np.maximum(weights, 1e-6)

    fine = masked_smooth(max(0.8, geometry_sigma * 0.42))
    coarse = masked_smooth(geometry_sigma)
    smooth = fine * 0.58 + coarse * 0.42

    # A bilateral pass keeps true part boundaries while flattening small
    # monocular-depth ripples on a single armour plate.
    depth_values = depth[valid]
    depth_span = float(np.percentile(depth_values, 98) - np.percentile(depth_values, 2))
    smooth = cv2.bilateralFilter(
        smooth.astype(np.float32),
        d=0,
        sigmaColor=max(depth_span * 0.045, 1e-5),
        sigmaSpace=max(1.25, geometry_sigma * 1.05),
    )

    height, width = depth.shape
    if intrinsics is None:
        focal = 0.9 * max(width, height)
        fx = fy = focal
        cx, cy = (width - 1) * 0.5, (height - 1) * 0.5
    else:
        camera = np.asarray(intrinsics, dtype=np.float32)
        fx, fy = float(camera[0, 0]), float(camera[1, 1])
        cx, cy = float(camera[0, 2]), float(camera[1, 2])

    yy, xx = np.mgrid[:height, :width].astype(np.float32)
    points = np.stack(
        ((xx - cx) * smooth / max(fx, 1e-6),
         (yy - cy) * smooth / max(fy, 1e-6),
         smooth),
        axis=-1,
    )
    tangent_y = np.gradient(points, axis=0)
    tangent_x = np.gradient(points, axis=1)
    normals = np.cross(tangent_x, tangent_y)
    normals /= np.maximum(np.linalg.norm(normals, axis=-1, keepdims=True), 1e-6)
    normals = np.where(normals[..., 2:3] < 0, -normals, normals)

    # Regularize the vector field itself. This produces broad, paintable NMM
    # bands instead of following every tiny depth fluctuation.
    normal_sigma = max(0.65, geometry_sigma * 0.38)
    normal_weight = gaussian_filter(valid_float, sigma=normal_sigma)
    for channel in range(3):
        normals[..., channel] = gaussian_filter(
            np.where(valid, normals[..., channel], 0.0), sigma=normal_sigma
        ) / np.maximum(normal_weight, 1e-6)
    normals /= np.maximum(np.linalg.norm(normals, axis=-1, keepdims=True), 1e-6)
    normals[mask <= 0.1] = (0, 0, 1)
    return normals


def depth_to_detail_normals(
    depth: np.ndarray,
    mask: np.ndarray,
    intrinsics: np.ndarray | None = None,
) -> np.ndarray:
    """Recover a lightly regularized normal field for zoomed micro structure."""
    depth = np.asarray(depth, dtype=np.float32)
    mask = np.asarray(mask, dtype=np.float32)
    valid = (mask > 0.1) & np.isfinite(depth) & (depth > 1e-6)
    if np.count_nonzero(valid) < 10:
        raise ValueError("The detected subject mask is empty or too small")

    valid_float = valid.astype(np.float32)
    detail_sigma = float(np.clip(max(depth.shape) / 1800.0, 0.38, 0.72))
    weights = gaussian_filter(valid_float, sigma=detail_sigma)
    smooth = gaussian_filter(
        np.where(valid, depth, 0.0), sigma=detail_sigma
    ) / np.maximum(weights, 1e-6)

    depth_values = depth[valid]
    depth_span = float(
        np.percentile(depth_values, 98) - np.percentile(depth_values, 2)
    )
    smooth = cv2.bilateralFilter(
        smooth.astype(np.float32),
        d=0,
        sigmaColor=max(depth_span * 0.018, 1e-6),
        sigmaSpace=max(0.65, detail_sigma),
    )

    height, width = depth.shape
    if intrinsics is None:
        focal = 0.9 * max(width, height)
        fx = fy = focal
        cx, cy = (width - 1) * 0.5, (height - 1) * 0.5
    else:
        camera = np.asarray(intrinsics, dtype=np.float32)
        fx, fy = float(camera[0, 0]), float(camera[1, 1])
        cx, cy = float(camera[0, 2]), float(camera[1, 2])

    yy, xx = np.mgrid[:height, :width].astype(np.float32)
    points = np.stack(
        (
            (xx - cx) * smooth / max(fx, 1e-6),
            (yy - cy) * smooth / max(fy, 1e-6),
            smooth,
        ),
        axis=-1,
    )
    tangent_y = np.gradient(points, axis=0)
    tangent_x = np.gradient(points, axis=1)
    normals = np.cross(tangent_x, tangent_y)
    normals /= np.maximum(np.linalg.norm(normals, axis=-1, keepdims=True), 1e-6)
    normals = np.where(normals[..., 2:3] < 0, -normals, normals)

    # Only remove isolated derivative noise. The broad normal field continues
    # to come from depth_to_normals; this field intentionally retains rivets,
    # seams and shallow relief visible after a high-resolution Zoom crop.
    normal_sigma = 0.28
    normal_weight = gaussian_filter(valid_float, sigma=normal_sigma)
    for channel in range(3):
        normals[..., channel] = gaussian_filter(
            np.where(valid, normals[..., channel], 0.0), sigma=normal_sigma
        ) / np.maximum(normal_weight, 1e-6)
    normals /= np.maximum(np.linalg.norm(normals, axis=-1, keepdims=True), 1e-6)
    normals[mask <= 0.1] = (0, 0, 1)
    return normals


def make_line_art(
    image: np.ndarray,
    mask: np.ndarray,
    normals: np.ndarray,
    depth: np.ndarray,
) -> np.ndarray:
    """Create clean black line work from silhouette and major 3D structures."""
    valid = mask > 0.1
    if np.count_nonzero(valid) < 10:
        raise ValueError("The detected subject mask is empty or too small")

    height, width = mask.shape
    diagonal = float(np.hypot(width, height))
    line_sigma = float(np.clip(max(mask.shape) / 420.0, 1.2, 2.8))
    smooth_mask = gaussian_filter(mask.astype(np.float32), sigma=line_sigma) > 0.48
    inner = binary_erosion(smooth_mask, iterations=max(2, int(round(line_sigma))))

    # A texture-free shape signal: coarse depth plus surface orientation. Paint
    # colour never enters this calculation, so printed motifs do not become ink.
    depth = np.asarray(depth, dtype=np.float32)
    depth_values = depth[valid & np.isfinite(depth)]
    low, high = np.percentile(depth_values, [2, 98])
    depth_unit = np.clip((depth - low) / max(float(high - low), 1e-6), 0, 1)
    depth_unit = gaussian_filter(depth_unit, sigma=line_sigma)
    shape_tone = (
        depth_unit * 0.58
        + (normals[..., 0] * 0.5 + 0.5) * 0.24
        + (normals[..., 1] * 0.5 + 0.5) * 0.18
    )
    shape_tone = gaussian_filter(shape_tone, sigma=line_sigma * 0.72)
    shape_u8 = np.clip(shape_tone * 255, 0, 255).astype(np.uint8)

    grad_x = cv2.Sobel(shape_u8, cv2.CV_32F, 1, 0, ksize=3)
    grad_y = cv2.Sobel(shape_u8, cv2.CV_32F, 0, 1, ksize=3)
    gradients = np.hypot(grad_x, grad_y)[inner]
    high_threshold = float(np.percentile(gradients, 90)) if gradients.size else 40.0
    high_threshold = max(18.0, high_threshold)
    structure = cv2.Canny(
        shape_u8,
        threshold1=high_threshold * 0.42,
        threshold2=high_threshold,
        L2gradient=True,
    )
    structure[~inner] = 0

    # Recover long sculptural seams that are clearer in the photograph than
    # in monocular depth. Strong denoising and length filtering discard most
    # paint texture and tiny scratches.
    gray = cv2.cvtColor(np.asarray(image, dtype=np.uint8), cv2.COLOR_RGB2GRAY)
    gray = cv2.bilateralFilter(
        gray,
        d=0,
        sigmaColor=42,
        sigmaSpace=max(4.0, line_sigma * 2.4),
    )
    gray = cv2.GaussianBlur(gray, (0, 0), sigmaX=line_sigma * 0.7)
    photo_dx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    photo_dy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    photo_gradients = np.hypot(photo_dx, photo_dy)[inner]
    photo_high = float(np.percentile(photo_gradients, 88)) if photo_gradients.size else 48.0
    photo_high = max(24.0, photo_high)
    photo_edges = cv2.Canny(
        gray,
        threshold1=photo_high * 0.46,
        threshold2=photo_high,
        L2gradient=True,
    )
    photo_edges[~inner] = 0
    structure = np.maximum(structure, photo_edges)
    structure = cv2.morphologyEx(
        structure,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)),
    )

    # Delete short islands and retain only continuous, paintable form lines.
    count, labels, stats, _ = cv2.connectedComponentsWithStats(structure, connectivity=8)
    cleaned = np.zeros_like(structure)
    min_length = max(18, int(round(diagonal * 0.022)))
    min_span = max(24, int(round(diagonal * 0.032)))
    for label in range(1, count):
        span = max(stats[label, cv2.CC_STAT_WIDTH], stats[label, cv2.CC_STAT_HEIGHT])
        if stats[label, cv2.CC_STAT_AREA] >= min_length and span >= min_span:
            cleaned[labels == label] = 255

    ink = np.zeros(mask.shape, dtype=np.uint8)
    internal_width = max(1, int(round(diagonal / 900)))
    if internal_width > 1:
        cleaned = cv2.dilate(
            cleaned,
            cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (internal_width, internal_width)),
        )
    ink = np.maximum(ink, (cleaned.astype(np.float32) * 0.72).astype(np.uint8))

    silhouette_mask = (smooth_mask.astype(np.uint8) * 255)
    contours, _ = cv2.findContours(silhouette_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    outline_width = max(2, int(round(diagonal / 520)))
    cv2.drawContours(ink, contours, -1, 255, outline_width, lineType=cv2.LINE_AA)

    # A slight sub-pixel softening is anti-aliasing, not fuzzy geometry.
    ink = gaussian_filter(ink.astype(np.float32), sigma=0.32)
    paper = 255.0 - np.clip(ink, 0, 255) * 0.96
    return np.repeat(paper[..., None], 3, axis=-1).astype(np.uint8)


def _half_vector(light: np.ndarray) -> np.ndarray:
    half = light + np.array([0, 0, 1], dtype=np.float32)
    return half / np.linalg.norm(half)


def render_nmm(
    image: np.ndarray,
    mask: np.ndarray,
    depth: np.ndarray,
    *,
    intrinsics: np.ndarray | None = None,
    normals: np.ndarray | None = None,
    palette: str = "silver",
    light: tuple[float, float, float] = (-0.42, 0.48, 0.76),
    steps: int = 5,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    if normals is None:
        normals = depth_to_normals(depth, mask, intrinsics)
    key = np.asarray(light, dtype=np.float32)
    key /= np.linalg.norm(key)
    half = _half_vector(key)
    secondary_light = np.array([-key[0] * 0.7, -0.78, 0.34], dtype=np.float32)
    secondary_light /= np.linalg.norm(secondary_light)
    tertiary_light = np.array([-.92 if key[0] >= 0 else .92, .18, .23], dtype=np.float32)
    tertiary_light /= np.linalg.norm(tertiary_light)
    half2, half3 = _half_vector(secondary_light), _half_vector(tertiary_light)

    diffuse = np.maximum(0, np.sum(normals * key, axis=-1))
    specular = np.maximum(0, np.sum(normals * half, axis=-1)) ** 38
    secondary = np.maximum(0, np.sum(normals * half2, axis=-1)) ** 18
    tertiary = np.maximum(0, np.sum(normals * half3, axis=-1)) ** 48
    rim = (1 - np.clip(normals[..., 2], 0, 1)) ** 3.2
    value = np.clip(.055 + diffuse * .51 + specular * .67 + rim * .2 + secondary * .17 + tertiary * .1, 0, .999)
    quantized = np.round(value * (steps - 1)) / (steps - 1)

    colors = PALETTES.get(palette, PALETTES["silver"])
    positions = quantized * (len(colors) - 1)
    low = np.floor(positions).astype(np.int32)
    high = np.minimum(low + 1, len(colors) - 1)
    fraction = (positions - low)[..., None]
    shaded = colors[low] * (1 - fraction) + colors[high] * fraction
    shaded[..., 0] = shaded[..., 0] * (1 - secondary[..., None][..., 0] * .08) + 105 * secondary * .08
    shaded[..., 1] = shaded[..., 1] * (1 - secondary[..., None][..., 0] * .12) + 190 * secondary * .12
    shaded[..., 2] = shaded[..., 2] * (1 - secondary[..., None][..., 0] * .16) + 220 * secondary * .16

    alpha = np.clip(mask[..., None], 0, 1) * .88
    final = image.astype(np.float32) * (1 - alpha) + shaded * alpha
    normal_rgb = ((normals + 1) * 127.5).astype(np.uint8)
    guide = (quantized * 255).astype(np.uint8)
    return np.clip(final, 0, 255).astype(np.uint8), normal_rgb, guide
