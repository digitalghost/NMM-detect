package com.digitalghost.nmmprobe;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Arrays;

/** Deterministic, explainable CPU NMM renderer driven by DA3 depth normals. */
final class NmmRenderer {
    private NmmRenderer() { }

    static Bitmap render(Bitmap source, float[] inputMask, float[] inputDepth) {
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        if (inputMask.length != count || inputDepth.length != count) {
            throw new IllegalArgumentException("蒙版、深度与预览尺寸不一致");
        }
        float[] mask = blur(inputMask, width, height, 2);
        float[] depth = normalizeDepth(inputDepth, inputMask);
        depth = maskedSmooth(depth, mask, width, height, 2);

        int[] sourcePixels = new int[count];
        int[] output = new int[count];
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height);
        float[] luminance = new float[count];
        for (int i = 0; i < count; i++) {
            int color = sourcePixels[i];
            luminance[i] = (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f
                    + Color.blue(color) * 0.0722f) / 255f;
        }

        float[] key = normalized(-0.42f, 0.48f, 0.76f);
        float[] secondary = normalized(0.30f, -0.78f, 0.34f);
        float[] tertiary = normalized(-0.92f, 0.18f, 0.23f);
        float[] halfKey = halfVector(key);
        float[] halfSecondary = halfVector(secondary);
        float[] halfTertiary = halfVector(tertiary);
        float normalScale = 52f;

        for (int y = 0; y < height; y++) {
            int up = Math.max(0, y - 1);
            int down = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float alpha = clamp(mask[index]);
                if (alpha < 0.015f) {
                    output[index] = sourcePixels[index];
                    continue;
                }
                int left = Math.max(0, x - 1);
                int right = Math.min(width - 1, x + 1);
                float center = depth[index];
                float dx = sampleDepth(depth, mask, y * width + left, center)
                        - sampleDepth(depth, mask, y * width + right, center);
                float dy = sampleDepth(depth, mask, up * width + x, center)
                        - sampleDepth(depth, mask, down * width + x, center);
                float nx = dx * normalScale;
                float ny = dy * normalScale;
                float nz = 1f;
                float inverseLength = inverseSqrt(nx * nx + ny * ny + nz * nz);
                nx *= inverseLength;
                ny *= inverseLength;
                nz *= inverseLength;

                float diffuse = Math.max(0f, dot(nx, ny, nz, key));
                float primary = pow(Math.max(0f, dot(nx, ny, nz, halfKey)), 34);
                float reflected = pow(Math.max(0f, dot(nx, ny, nz, halfSecondary)), 18);
                float accent = pow(Math.max(0f, dot(nx, ny, nz, halfTertiary)), 46);
                float rim = pow(1f - Math.max(0f, nz), 3.1f);
                float localEdge = edge(luminance, width, height, x, y);
                float value = 0.055f + diffuse * 0.25f + primary * 0.72f
                        + reflected * 0.34f + accent * 0.22f + rim * 0.15f;
                value -= Math.min(0.18f, localEdge * 0.30f);
                value = quantize(clamp(value), 7);

                int[] silver = silver(value);
                // Cool secondary reflection and warm tertiary accent stay distinct.
                silver[1] = clamp255(silver[1] + Math.round(reflected * 14f + accent * 5f));
                silver[2] = clamp255(silver[2] + Math.round(reflected * 25f));
                silver[0] = clamp255(silver[0] + Math.round(accent * 20f));
                float composite = Math.min(0.94f, alpha * 0.90f);
                int original = sourcePixels[index];
                int red = mix(Color.red(original), silver[0], composite);
                int green = mix(Color.green(original), silver[1], composite);
                int blue = mix(Color.blue(original), silver[2], composite);
                output[index] = Color.rgb(red, green, blue);
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(output, 0, width, 0, 0, width, height);
        return result;
    }

    private static float[] normalizeDepth(float[] depth, float[] mask) {
        float[] samples = new float[Math.max(1, depth.length / 4)];
        int size = 0;
        for (int i = 0; i < depth.length; i += 4) {
            float value = depth[i];
            if (mask[i] > 0.1f && Float.isFinite(value) && value > 1e-7f) samples[size++] = value;
        }
        if (size < 16) throw new IllegalStateException("有效深度点不足");
        Arrays.sort(samples, 0, size);
        float low = samples[Math.min(size - 1, Math.round((size - 1) * 0.02f))];
        float high = samples[Math.min(size - 1, Math.round((size - 1) * 0.98f))];
        float range = Math.max(1e-7f, high - low);
        float[] result = new float[depth.length];
        for (int i = 0; i < depth.length; i++) {
            float value = depth[i];
            result[i] = Float.isFinite(value) ? clamp((value - low) / range) : 0.5f;
        }
        return result;
    }

    private static float[] maskedSmooth(float[] source, float[] mask, int width, int height, int passes) {
        float[] current = source.clone();
        for (int pass = 0; pass < passes; pass++) {
            float[] next = current.clone();
            for (int y = 0; y < height; y++) {
                int top = Math.max(0, y - 1);
                int bottom = Math.min(height - 1, y + 1);
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (mask[index] < 0.03f) continue;
                    float sum = 0f;
                    float weight = 0f;
                    for (int yy = top; yy <= bottom; yy++) {
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                            int neighbor = yy * width + xx;
                            float w = mask[neighbor];
                            sum += current[neighbor] * w;
                            weight += w;
                        }
                    }
                    if (weight > 1e-5f) next[index] = sum / weight;
                }
            }
            current = next;
        }
        return current;
    }

    private static float[] blur(float[] source, int width, int height, int passes) {
        float[] current = source.clone();
        for (int pass = 0; pass < passes; pass++) {
            float[] next = new float[current.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float sum = 0f;
                    int count = 0;
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(height - 1, y + 1); yy++) {
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                            sum += current[yy * width + xx];
                            count++;
                        }
                    }
                    next[y * width + x] = sum / count;
                }
            }
            current = next;
        }
        return current;
    }

    private static float edge(float[] luminance, int width, int height, int x, int y) {
        int left = y * width + Math.max(0, x - 1);
        int right = y * width + Math.min(width - 1, x + 1);
        int up = Math.max(0, y - 1) * width + x;
        int down = Math.min(height - 1, y + 1) * width + x;
        return Math.abs(luminance[right] - luminance[left])
                + Math.abs(luminance[down] - luminance[up]);
    }

    private static float sampleDepth(float[] depth, float[] mask, int index, float fallback) {
        return mask[index] > 0.1f ? depth[index] : fallback;
    }

    private static int[] silver(float value) {
        float[] dark = {13f, 18f, 28f};
        float[] middle = {91f, 108f, 132f};
        float[] bright = {239f, 247f, 255f};
        int[] result = new int[3];
        if (value < 0.56f) {
            float t = value / 0.56f;
            for (int i = 0; i < 3; i++) result[i] = Math.round(dark[i] * (1f - t) + middle[i] * t);
        } else {
            float t = (value - 0.56f) / 0.44f;
            for (int i = 0; i < 3; i++) result[i] = Math.round(middle[i] * (1f - t) + bright[i] * t);
        }
        return result;
    }

    private static float[] normalized(float x, float y, float z) {
        float inverse = inverseSqrt(x * x + y * y + z * z);
        return new float[]{x * inverse, y * inverse, z * inverse};
    }

    private static float[] halfVector(float[] light) {
        return normalized(light[0], light[1], light[2] + 1f);
    }

    private static float dot(float x, float y, float z, float[] vector) {
        return x * vector[0] + y * vector[1] + z * vector[2];
    }

    private static float inverseSqrt(float value) {
        return 1f / (float) Math.sqrt(Math.max(value, 1e-12f));
    }

    private static float pow(float value, int power) {
        return (float) Math.pow(value, power);
    }

    private static float pow(float value, float power) {
        return (float) Math.pow(value, power);
    }

    private static float quantize(float value, int steps) {
        return Math.round(value * (steps - 1)) / (float) (steps - 1);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int mix(int from, int to, float amount) {
        return clamp255(Math.round(from * (1f - amount) + to * amount));
    }
}
