package com.digitalghost.nmmprobe;

import android.graphics.Bitmap;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Runs the exact desktop SAM 3 and DA3 exports sequentially through XNNPACK. */
final class ModelRunner {
    private static final int SAM_SIZE = 1008;
    private static final int DA3_HEIGHT = 1008;
    private static final int DA3_WIDTH = 756;
    private static final long[] SAM_SHAPE = {1, 3, SAM_SIZE, SAM_SIZE};
    private static final long[] DA3_SHAPE = {1, 1, 3, DA3_HEIGHT, DA3_WIDTH};

    private final File samModel;
    private final File da3Model;

    ModelRunner(File samModel, File da3Model) {
        this.samModel = samModel;
        this.da3Model = da3Model;
    }

    float[] runSam(Bitmap source) throws Exception {
        Bitmap inputBitmap = Bitmap.createScaledBitmap(source, SAM_SIZE, SAM_SIZE, true);
        FloatBuffer input = imageBuffer(inputBitmap, false);
        if (inputBitmap != source) inputBitmap.recycle();

        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = cpuOptions(false);
             OrtSession session = environment.createSession(samModel.getAbsolutePath(), options);
             OnnxTensor tensor = OnnxTensor.createTensor(environment, input, SAM_SHAPE);
             OrtSession.Result result = session.run(Map.of("pixel_values", tensor))) {
            OnnxTensor masks = (OnnxTensor) result.get(0);
            OnnxTensor logits = (OnnxTensor) result.get(2);
            OnnxTensor presence = (OnnxTensor) result.get(3);
            long[] shape = ((TensorInfo) masks.getInfo()).getShape();
            if (shape.length < 3) throw new IllegalStateException("SAM 3 蒙版输出形状异常");
            int maskHeight = (int) shape[shape.length - 2];
            int maskWidth = (int) shape[shape.length - 1];
            int queries = (int) shape[shape.length - 3];
            int plane = maskHeight * maskWidth;
            FloatBuffer maskValues = masks.getFloatBuffer();
            FloatBuffer scoreValues = logits.getFloatBuffer();
            FloatBuffer presenceValues = presence.getFloatBuffer();
            float presenceScore = sigmoid(presenceValues.get(presenceValues.position()));
            int best = -1;
            float bestRank = -1f;
            float thresholdLogit = (float) Math.log(0.45 / 0.55);
            int maskBase = maskValues.position();
            int scoreBase = scoreValues.position();
            int scoreCount = scoreValues.remaining();
            float maximumScore = -1f;
            int maximumQuery = -1;
            for (int query = 0; query < queries; query++) {
                float score = sigmoid(scoreValues.get(scoreBase + Math.min(query, scoreCount - 1)))
                        * presenceScore;
                if (score > maximumScore) {
                    maximumScore = score;
                    maximumQuery = query;
                }
                // The exported ORT graph has a lower calibrated score range than
                // the eager PyTorch path. 0.10 accepts the validated Web image
                // (0.149) while still rejecting the transparent cutout (0.047).
                if (score < 0.10f) continue;
                int area = 0;
                int offset = maskBase + query * plane;
                for (int index = 0; index < plane; index++) {
                    if (maskValues.get(offset + index) > thresholdLogit) area++;
                }
                float rank = score * (float) Math.sqrt(area / (float) plane);
                if (rank > bestRank) {
                    bestRank = rank;
                    best = query;
                }
            }
            android.util.Log.i("NMMAndroid", String.format(Locale.US,
                    "SAM output=%s presence=%.5f maxScore=%.5f maxQuery=%d selected=%d rank=%.5f",
                    java.util.Arrays.toString(shape), presenceScore, maximumScore,
                    maximumQuery, best, bestRank));
            if (best < 0) {
                throw new IllegalStateException(String.format(Locale.US,
                        "SAM 3 未通过阈值（presence %.3f，最高分 %.3f）",
                        presenceScore, maximumScore));
            }
            float[] smallMask = new float[plane];
            int selected = maskBase + best * plane;
            for (int index = 0; index < plane; index++) {
                smallMask[index] = maskValues.get(selected + index) > thresholdLogit ? 1f : 0f;
            }
            return resizeBilinear(smallMask, maskWidth, maskHeight,
                    source.getWidth(), source.getHeight());
        }
    }

    static final class DepthResult {
        final float[] depth;
        final float[] camera;
        DepthResult(float[] depth, float[] camera) { this.depth = depth; this.camera = camera; }
    }

    DepthResult runDa3(Bitmap source) throws Exception {
        // Fixed-shape ONNX: preserve aspect ratio and pad, never stretch the subject.
        float scale = Math.min(DA3_WIDTH / (float)source.getWidth(), DA3_HEIGHT / (float)source.getHeight());
        int resizedWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int resizedHeight = Math.max(1, Math.round(source.getHeight() * scale));
        int left = (DA3_WIDTH - resizedWidth) / 2, top = (DA3_HEIGHT - resizedHeight) / 2;
        Bitmap inputBitmap = Bitmap.createBitmap(DA3_WIDTH, DA3_HEIGHT, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas inputCanvas = new android.graphics.Canvas(inputBitmap);
        inputCanvas.drawColor(android.graphics.Color.rgb(124, 116, 104));
        inputCanvas.drawBitmap(source, null, new android.graphics.Rect(left, top, left + resizedWidth, top + resizedHeight),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        FloatBuffer input = imageBuffer(inputBitmap, true);
        if (inputBitmap != source) inputBitmap.recycle();

        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = cpuOptions(true);
             OrtSession session = environment.createSession(da3Model.getAbsolutePath(), options);
             OnnxTensor tensor = OnnxTensor.createTensor(environment, input, DA3_SHAPE);
             OrtSession.Result result = session.run(Map.of("image", tensor))) {
            OnnxTensor depthTensor = (OnnxTensor) result.get(0);
            long[] shape = ((TensorInfo) depthTensor.getInfo()).getShape();
            int height = (int) shape[shape.length - 2];
            int width = (int) shape[shape.length - 1];
            int count = height * width;
            FloatBuffer values = depthTensor.getFloatBuffer();
            int base = values.position();
            float[] depth = new float[count];
            for (int index = 0; index < count; index++) depth[index] = values.get(base + index);
            if (result.size() < 2) throw new IllegalStateException("DA3 模型缺少相机内参输出，请更新模型");
            FloatBuffer intrinsics = ((OnnxTensor)result.get(1)).getFloatBuffer();
            if (intrinsics.remaining() != 9) throw new IllegalStateException("DA3 相机内参维度错误");
            float[] camera = new float[9]; intrinsics.get(camera);
            for (float value : camera) if (!Float.isFinite(value)) throw new IllegalStateException("DA3 相机内参无效");
            if (camera[0] <= 0 || camera[4] <= 0) throw new IllegalStateException("DA3 焦距无效");
            camera = DepthCoordinates.unpadCamera(camera, left, top, resizedWidth, resizedHeight, source.getWidth(), source.getHeight());
            float[] restored = DepthCoordinates.unpadDepth(depth, width, height, DA3_WIDTH, DA3_HEIGHT,
                    left, top, resizedWidth, resizedHeight, source.getWidth(), source.getHeight());
            return new DepthResult(restored, camera);
        }
    }

    private static OrtSession.SessionOptions cpuOptions(boolean xnnpack) throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(6);
        options.setInterOpNumThreads(1);
        options.setMemoryPatternOptimization(false);
        options.setCPUArenaAllocator(false);
        if (xnnpack) {
            options.addXnnpack(Collections.singletonMap("intra_op_num_threads", "6"));
        }
        return options;
    }

    private static FloatBuffer imageBuffer(Bitmap bitmap, boolean imageNet) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int plane = width * height;
        int[] pixels = new int[plane];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        FloatBuffer buffer = FloatBuffer.allocate(plane * 3);
        float[] mean = imageNet ? new float[]{0.485f, 0.456f, 0.406f}
                : new float[]{0.5f, 0.5f, 0.5f};
        float[] std = imageNet ? new float[]{0.229f, 0.224f, 0.225f}
                : new float[]{0.5f, 0.5f, 0.5f};
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel == 0 ? 16 : channel == 1 ? 8 : 0;
            for (int pixel : pixels) {
                float value = ((pixel >> shift) & 0xff) / 255f;
                buffer.put((value - mean[channel]) / std[channel]);
            }
        }
        buffer.rewind();
        return buffer;
    }

    static Bitmap fitPreview(Bitmap source, int longestSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= longestSide && source.getConfig() == Bitmap.Config.ARGB_8888) return source;
        float scale = Math.min(1f, longestSide / (float) longest);
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private static float[] resizeBilinear(float[] source, int sourceWidth, int sourceHeight,
                                           int targetWidth, int targetHeight) {
        float[] result = new float[targetWidth * targetHeight];
        float xScale = sourceWidth / (float) targetWidth;
        float yScale = sourceHeight / (float) targetHeight;
        for (int y = 0; y < targetHeight; y++) {
            float sourceY = Math.max(0f, (y + 0.5f) * yScale - 0.5f);
            int y0 = Math.min(sourceHeight - 1, (int) sourceY);
            int y1 = Math.min(sourceHeight - 1, y0 + 1);
            float fy = sourceY - y0;
            for (int x = 0; x < targetWidth; x++) {
                float sourceX = Math.max(0f, (x + 0.5f) * xScale - 0.5f);
                int x0 = Math.min(sourceWidth - 1, (int) sourceX);
                int x1 = Math.min(sourceWidth - 1, x0 + 1);
                float fx = sourceX - x0;
                float top = source[y0 * sourceWidth + x0] * (1f - fx)
                        + source[y0 * sourceWidth + x1] * fx;
                float bottom = source[y1 * sourceWidth + x0] * (1f - fx)
                        + source[y1 * sourceWidth + x1] * fx;
                result[y * targetWidth + x] = top * (1f - fy) + bottom * fy;
            }
        }
        return result;
    }

    private static float sigmoid(float value) {
        if (value >= 0f) {
            float z = (float) Math.exp(-value);
            return 1f / (1f + z);
        }
        float z = (float) Math.exp(value);
        return z / (1f + z);
    }
}
