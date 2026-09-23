package com.digitalghost.nmmprobe;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Arrays;

/** Builds the same browser-facing geometry maps as the desktop pipeline. */
final class ArtifactGenerator {
    static final class Result {
        final Bitmap mask;
        final Bitmap depth;
        final Bitmap normals;
        final Bitmap detailNormals;
        final Bitmap lineart;

        Result(Bitmap mask, Bitmap depth, Bitmap normals, Bitmap detailNormals, Bitmap lineart) {
            this.mask = mask;
            this.depth = depth;
            this.normals = normals;
            this.detailNormals = detailNormals;
            this.lineart = lineart;
        }

        void recycle() {
            mask.recycle();
            depth.recycle();
            normals.recycle();
            detailNormals.recycle();
            lineart.recycle();
        }
    }

    private ArtifactGenerator() { }

    static Result generate(Bitmap source, float[] inputMask, float[] inputDepth) {
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        if (inputMask.length != count || inputDepth.length != count) {
            throw new IllegalArgumentException("形体数据与照片尺寸不一致");
        }

        float[] mask = blur(clampMask(inputMask), width, height, 2);
        float[] unitDepth = normalizeDepth(inputDepth, mask);
        float[] broadDepth = maskedSmooth(unitDepth, mask, width, height, 4);
        float[] detailDepth = maskedSmooth(unitDepth, mask, width, height, 1);
        int[] sourcePixels = new int[count];
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height);
        float[] luminance = new float[count];
        for (int i = 0; i < count; i++) {
            int color = sourcePixels[i];
            luminance[i] = (Color.red(color) * .2126f + Color.green(color) * .7152f
                    + Color.blue(color) * .0722f) / 255f;
        }
        float[] softLuminance = blur(luminance, width, height, 1);

        int[] maskPixels = new int[count];
        int[] depthPixels = new int[count];
        int[] normalPixels = new int[count];
        int[] detailPixels = new int[count];
        int[] linePixels = new int[count];
        float detailGain = Math.max(width, height) / 92f;
        float broadGain = Math.max(width, height) / 64f;

        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1);
            int yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1);
                int xp = Math.min(width - 1, x + 1);
                int index = y * width + x;
                float alpha = clamp(mask[index]);
                int grayMask = clamp255(Math.round(alpha * 255f));
                int grayDepth = alpha > .04f ? clamp255(Math.round(unitDepth[index] * 255f)) : 0;
                maskPixels[index] = Color.rgb(grayMask, grayMask, grayMask);
                depthPixels[index] = Color.rgb(grayDepth, grayDepth, grayDepth);

                float broadDx = (broadDepth[y * width + xp] - broadDepth[y * width + xm]) * broadGain;
                float broadDy = (broadDepth[yp * width + x] - broadDepth[ym * width + x]) * broadGain;
                normalPixels[index] = encodeNormal(-broadDx, -broadDy, alpha);

                float detailDx = (detailDepth[y * width + xp] - detailDepth[y * width + xm]) * detailGain;
                float detailDy = (detailDepth[yp * width + x] - detailDepth[ym * width + x]) * detailGain;
                detailPixels[index] = encodeNormal(-detailDx, -detailDy, alpha);

                float neighborMask = Math.min(Math.min(mask[y * width + xm], mask[y * width + xp]),
                        Math.min(mask[ym * width + x], mask[yp * width + x]));
                float silhouette = alpha > .22f && neighborMask < .16f ? 1f : 0f;
                float depthEdge = (Math.abs(broadDx) + Math.abs(broadDy)) * .42f;
                float photoDx = Math.abs(softLuminance[y * width + xp] - softLuminance[y * width + xm]);
                float photoDy = Math.abs(softLuminance[yp * width + x] - softLuminance[ym * width + x]);
                float photoEdge = Math.max(0f, (photoDx + photoDy - .075f) * 3.8f);
                float ink = Math.max(silhouette, Math.max(depthEdge, photoEdge * .68f)) * alpha;
                ink = clamp((ink - .08f) * 1.32f);
                int paper = clamp255(Math.round(255f - ink * 244f));
                linePixels[index] = Color.rgb(paper, paper, paper);
            }
        }

        return new Result(bitmap(width, height, maskPixels), bitmap(width, height, depthPixels),
                bitmap(width, height, normalPixels), bitmap(width, height, detailPixels),
                bitmap(width, height, linePixels));
    }

    private static int encodeNormal(float x, float y, float alpha) {
        if (alpha <= .02f) return Color.rgb(128, 128, 255);
        float inverse = 1f / (float) Math.sqrt(Math.max(1e-12f, x * x + y * y + 1f));
        return Color.rgb(clamp255(Math.round((x * inverse + 1f) * 127.5f)),
                clamp255(Math.round((y * inverse + 1f) * 127.5f)),
                clamp255(Math.round((inverse + 1f) * 127.5f)));
    }

    private static Bitmap bitmap(int width, int height, int[] pixels) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static float[] clampMask(float[] source) {
        float[] output = source.clone();
        for (int i = 0; i < output.length; i++) output[i] = clamp(output[i]);
        return output;
    }

    private static float[] normalizeDepth(float[] depth, float[] mask) {
        float[] samples = new float[Math.max(1, depth.length)];
        int used = 0;
        for (int i = 0; i < depth.length; i++) {
            if (mask[i] > .1f && Float.isFinite(depth[i])) samples[used++] = depth[i];
        }
        if (used < 10) throw new IllegalArgumentException("主体蒙版为空，无法恢复深度");
        Arrays.sort(samples, 0, used);
        float low = samples[Math.min(used - 1, Math.round((used - 1) * .02f))];
        float high = samples[Math.min(used - 1, Math.round((used - 1) * .98f))];
        float span = Math.max(1e-6f, high - low);
        float[] output = new float[depth.length];
        for (int i = 0; i < depth.length; i++) {
            output[i] = clamp((depth[i] - low) / span);
        }
        return output;
    }

    private static float[] maskedSmooth(float[] source, float[] mask, int width, int height, int passes) {
        float[] current = source.clone();
        for (int pass = 0; pass < passes; pass++) {
            float[] next = new float[current.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (mask[index] <= .02f) { next[index] = current[index]; continue; }
                    float sum = 0f;
                    float weight = 0f;
                    for (int oy = -1; oy <= 1; oy++) {
                        int sy = Math.max(0, Math.min(height - 1, y + oy));
                        for (int ox = -1; ox <= 1; ox++) {
                            int sx = Math.max(0, Math.min(width - 1, x + ox));
                            int sample = sy * width + sx;
                            float w = mask[sample] * (ox == 0 && oy == 0 ? 2f : 1f);
                            sum += current[sample] * w;
                            weight += w;
                        }
                    }
                    next[index] = weight > 1e-5f ? sum / weight : current[index];
                }
            }
            current = next;
        }
        return current;
    }

    private static float[] blur(float[] source, int width, int height, int passes) {
        float[] current = source.clone();
        for (int pass = 0; pass < passes; pass++) {
            float[] horizontal = new float[current.length];
            float[] next = new float[current.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int left = y * width + Math.max(0, x - 1);
                    int center = y * width + x;
                    int right = y * width + Math.min(width - 1, x + 1);
                    horizontal[center] = (current[left] + current[center] * 2f + current[right]) * .25f;
                }
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int top = Math.max(0, y - 1) * width + x;
                    int center = y * width + x;
                    int bottom = Math.min(height - 1, y + 1) * width + x;
                    next[center] = (horizontal[top] + horizontal[center] * 2f + horizontal[bottom]) * .25f;
                }
            }
            current = next;
        }
        return current;
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static int clamp255(int value) { return Math.max(0, Math.min(255, value)); }
}
