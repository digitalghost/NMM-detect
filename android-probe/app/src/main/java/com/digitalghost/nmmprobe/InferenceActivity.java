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

/**
 * Transparent, top-priority, one-shot inference process. Each model gets a
 * fresh process because ORT/XNNPACK retains its native arena after close().
 */
public final class InferenceActivity extends Activity {
    static final String ACTION_RESULT = "com.digitalghost.nmmprobe.INFERENCE_RESULT";
    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_ERROR = "error";
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
            bitmap = BitmapFactory.decodeFile(new File(getCacheDir(), INPUT_FILE).getAbsolutePath());
            if (bitmap == null) throw new IllegalStateException("无法读取待分析图片");
            ModelRunner runner = new ModelRunner(
                    new File(getFilesDir(), "sam3-miniature-1008.onnx"),
                    new File(getFilesDir(), "da3-large-1008x756.onnx"));
            float[] values;
            File output;
            if (STAGE_SAM.equals(stage)) {
                values = runner.runSam(bitmap);
                output = new File(getCacheDir(), MASK_FILE);
            } else {
                values = runner.runDa3(bitmap);
                output = new File(getCacheDir(), DEPTH_FILE);
            }
            writeArray(output, bitmap.getWidth(), bitmap.getHeight(), values);
        } catch (Throwable failure) {
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            android.util.Log.e("NMMAndroid", stage + " isolated inference failed", failure);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }

        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_STAGE, stage);
        if (error != null) result.putExtra(EXTRA_ERROR, error);
        sendBroadcast(result);
        runOnUiThread(this::finishAndRelease);
    }

    private void finishAndRelease() {
        finish();
        // The process itself is the deterministic XNNPACK memory boundary.
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Process.killProcess(Process.myPid()), 400);
    }

    private static void writeArray(File file, int width, int height, float[] values)
            throws Exception {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024))) {
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(values.length);
            for (float value : values) output.writeFloat(value);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
