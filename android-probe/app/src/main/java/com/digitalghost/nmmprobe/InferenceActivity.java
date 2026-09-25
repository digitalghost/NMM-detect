package com.digitalghost.nmmprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-shot isolated model process. ORT native arenas disappear with it. */
public final class InferenceActivity extends Activity {
    static final String ACTION_RESULT = "com.digitalghost.nmmprobe.INFERENCE_RESULT";
    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_INPUT_FILE = "input_file";
    static final String EXTRA_OUTPUT_FILE = "output_file";
    static final String STAGE_SAM = "sam";
    static final String STAGE_DA3 = "da3";
    static final String INPUT_FILE = "analysis-input.png";
    static final String MASK_FILE = "analysis-mask.bin";
    static final String DEPTH_FILE = "analysis-depth.bin";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String stage = getIntent().getStringExtra(EXTRA_STAGE);
        if (!STAGE_SAM.equals(stage) && !STAGE_DA3.equals(stage)) {
            finishAndRelease();
            return;
        }
        worker.execute(() -> execute(stage));
    }

    private void execute(String stage) {
        String error = null;
        Bitmap bitmap = null;
        try {
            String inputName = getIntent().getStringExtra(EXTRA_INPUT_FILE);
            if (inputName == null || inputName.isBlank()) inputName = INPUT_FILE;
            String outputName = getIntent().getStringExtra(EXTRA_OUTPUT_FILE);
            if (outputName == null || outputName.isBlank()) {
                outputName = STAGE_SAM.equals(stage) ? MASK_FILE : DEPTH_FILE;
            }
            bitmap = BitmapFactory.decodeFile(new File(getCacheDir(), inputName).getAbsolutePath());
            if (bitmap == null) throw new IllegalStateException("无法读取待分析图片");
            ModelRunner runner = new ModelRunner(
                    new File(getFilesDir(), "sam3-miniature-1008.onnx"),
                    new File(getFilesDir(), "da3-large-1008x756.onnx"));
            ModelRunner.DepthResult depth = STAGE_SAM.equals(stage) ? null : runner.runDa3(bitmap);
            float[] values = depth == null ? runner.runSam(bitmap) : depth.depth;
            writeArray(new File(getCacheDir(), outputName), bitmap.getWidth(), bitmap.getHeight(), values,
                    depth == null ? null : depth.camera);
        } catch (Throwable failure) {
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            android.util.Log.e("NMMAndroid", "Local inference failed", failure);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_STAGE, stage);
        result.putExtra(EXTRA_ERROR, error);
        sendBroadcast(result);
        new Handler(Looper.getMainLooper()).postDelayed(this::finishAndRelease, 220);
    }

    private void finishAndRelease() {
        // This transparent activity shares the editor's task. Removing the task
        // would also close StudioActivity after every model stage.
        finish();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Process.killProcess(Process.myPid()), 120);
    }

    private static void writeArray(File file, int width, int height, float[] values, float[] camera)
            throws Exception {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024))) {
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(values.length);
            for (float value : values) output.writeFloat(value);
            if (camera != null) for (float value : camera) output.writeFloat(value);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
