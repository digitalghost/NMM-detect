"""Read-only diagnostic: pre-0.3.3 Android approximation versus Web geometry.

Run with the project virtualenv from the repository root. Synthetic cases use
identical metric depth/mask on both paths; real cache statistics are NOT treated
as a paired experiment unless the original sources are known to match.
"""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import convolve

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.nmm_shader import depth_to_normals


def android_normals(depth, mask):
    # Equivalent to ArtifactGenerator's broad-normal path (before PNG rounding).
    kernel = np.array([1, 2, 1], dtype=np.float32) / 4
    for _ in range(2):
        mask = convolve(convolve(mask, kernel[None, :], mode='nearest'), kernel[:, None], mode='nearest')
    samples = np.sort(depth[(mask > .12) & np.isfinite(depth)])
    lo, hi = samples[np.floor((len(samples)-1)*np.array([.02, .98])+.5).astype(int)]
    smooth = np.clip((depth-lo)/max(float(hi-lo), 1e-6), 0, 1)
    kernel = np.ones((3, 3), dtype=np.float32)
    kernel[1, 1] = 2
    for _ in range(4):
        weights = convolve(mask, kernel, mode='nearest')
        values = convolve(smooth*mask, kernel, mode='nearest')
        smooth = np.where(mask > .02, values/np.maximum(weights, 1e-5), smooth)
    padded = np.pad(smooth, 1, mode='edge')
    gain = max(depth.shape)/64
    dx = (padded[1:-1, 2:]-padded[1:-1, :-2])*gain
    dy = (padded[2:, 1:-1]-padded[:-2, 1:-1])*gain
    normals = np.stack([-dx, -dy, np.ones_like(dx)], axis=-1)
    return normals/np.linalg.norm(normals, axis=-1, keepdims=True)


def stats(normals, mask):
    n = normals[mask > .9]
    n = n/np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-6)
    tilt = np.degrees(np.arccos(np.clip(n[:, 2], -1, 1)))
    light = np.array([-.38, .42, np.sqrt(1-.38**2-.42**2)])
    diffuse = np.maximum(0, n @ light)
    return {'tiltDegreesP10P50P90': np.percentile(tilt, [10, 50, 90]).round(3).tolist(),
            'diffuseP10P50P90': np.percentile(diffuse, [10, 50, 90]).round(4).tolist()}


def main():
    height, width = 384, 384
    y, x = np.mgrid[-1:1:complex(height), -1:1:complex(width)].astype(np.float32)
    mask = ((x*x+y*y) < .8**2).astype(np.float32)
    report = {'syntheticSameInput': {}}
    for amplitude in [.05, .2, .5]:
        depth = 2 + amplitude*(x*x+y*y)
        report['syntheticSameInput'][str(amplitude)] = {
            'android': stats(android_normals(depth, mask), mask),
            'web': stats(depth_to_normals(depth, mask), mask)}
    fixture = Path('dist/android-web-comparison/android-fixture')
    if fixture.exists():
        normals = np.asarray(Image.open(fixture/'normals.png').convert('RGB'), dtype=np.float32)/127.5-1
        real_mask = np.asarray(Image.open(fixture/'mask.png').convert('L'), dtype=np.float32)/255
        report['androidRealFixture'] = stats(normals, real_mask)
        target = np.asarray(Image.open(fixture/'source.png').convert('RGB').resize((32, 32)), dtype=float)
        matches = []
        for path in Path('output').glob('*/cutout.png'):
            image = np.asarray(Image.open(path).convert('RGB').resize((32, 32)), dtype=float)
            matches.append((float(np.mean(np.abs(image-target))), str(path)))
        report['closestWebSourcesNotGuaranteedIdentical'] = sorted(matches)[:3]
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
