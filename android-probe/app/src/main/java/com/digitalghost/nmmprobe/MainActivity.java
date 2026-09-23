package com.digitalghost.nmmprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CPU-first minimum viable NMM application. */
public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private static final String DA3_MODEL = "da3-large-1008x756.onnx";
    private static final String SAM_MODEL = "sam3-miniature-1008.onnx";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ComparisonView preview;
    private Button chooseButton;
    private Button analyzeButton;
    private Button exportButton;
    private SeekBar compareSlider;
    private TextView statusView;
    private TextView detailView;
    private ProgressBar progress;
    private Bitmap sourceBitmap;
    private Bitmap nmmBitmap;
    private boolean busy;
    private long analysisStarted;
    private boolean receiverRegistered;

    private final BroadcastReceiver inferenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!InferenceActivity.ACTION_RESULT.equals(intent.getAction()) || !busy) return;
            String stage = intent.getStringExtra(InferenceActivity.EXTRA_STAGE);
            String error = intent.getStringExtra(InferenceActivity.EXTRA_ERROR);
            if (error != null) {
                showFailure("分析失败", new IllegalStateException(error));
                setBusy(false);
                return;
            }
            if (InferenceActivity.STAGE_SAM.equals(stage)) {
                statusView.setText("主体识别完成，正在释放内存");
                detailView.setText("SAM 3 推理进程已结束；即将启动 DA3-LARGE-1.1。");
                mainHandler.postDelayed(() -> startInference(InferenceActivity.STAGE_DA3), 1400);
            } else if (InferenceActivity.STAGE_DA3.equals(stage)) {
                finishRendering();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(8, 10, 14));
        getWindow().setNavigationBarColor(Color.rgb(8, 10, 14));
        buildInterface();
        IntentFilter filter = new IntentFilter(InferenceActivity.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(inferenceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(inferenceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        receiverRegistered = true;
        updateModelStatus();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(16));
        root.setBackgroundColor(Color.rgb(8, 10, 14));

        TextView eyebrow = text("NMM · LOCAL STUDIO", 11, 0xff92a4ff);
        eyebrow.setLetterSpacing(0.16f);
        root.addView(eyebrow, matchWrap());

        TextView title = text("微缩模型光影预演", 25, Color.WHITE);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        title.setPadding(0, dp(3), 0, 0);
        root.addView(title, matchWrap());

        TextView subtitle = text("SAM 3 主体识别 · DA3 深度 · 完全本地推理", 13, 0xff89909f);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle, matchWrap());

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(0xff11151d);
        preview = new ComparisonView(this);
        preview.setOnComparisonChangedListener(value -> {
            int progressValue = Math.round(value * 100f);
            if (compareSlider != null && compareSlider.getProgress() != progressValue) {
                compareSlider.setProgress(progressValue);
            }
        });
        previewFrame.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(52), dp(52));
        progressParams.gravity = Gravity.CENTER;
        previewFrame.addView(progress, progressParams);
        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout compareRow = new LinearLayout(this);
        compareRow.setGravity(Gravity.CENTER_VERTICAL);
        compareRow.setPadding(0, dp(10), 0, dp(6));
        compareRow.addView(text("光影", 12, 0xffcbd3e6), wrapWrap());
        compareSlider = new SeekBar(this);
        compareSlider.setMax(100);
        compareSlider.setProgress(50);
        compareSlider.setEnabled(false);
        compareSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                preview.setComparison(value / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        compareRow.addView(compareSlider, new LinearLayout.LayoutParams(0, dp(34), 1f));
        compareRow.addView(text("原图", 12, 0xffcbd3e6), wrapWrap());
        root.addView(compareRow, matchWrap());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        chooseButton = button("选择照片", true);
        chooseButton.setOnClickListener(v -> choosePhoto());
        actionRow.addView(chooseButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        analyzeButton = button("重新分析", false);
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(v -> analyze());
        LinearLayout.LayoutParams spaced = new LinearLayout.LayoutParams(0, dp(48), 1f);
        spaced.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(analyzeButton, spaced);
        exportButton = button("保存 PNG", false);
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportPng());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        exportParams.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(exportButton, exportParams);
        root.addView(actionRow, matchWrap());

        statusView = text("正在检查本地模型…", 14, 0xffe9edf7);
        statusView.setTypeface(Typeface.create("sans", Typeface.BOLD));
        statusView.setPadding(0, dp(13), 0, dp(2));
        root.addView(statusView, matchWrap());
        detailView = text("", 12, 0xff7f8796);
        root.addView(detailView, matchWrap());
        setContentView(root);
    }

    private void updateModelStatus() {
        File da3 = new File(getFilesDir(), DA3_MODEL);
        File da3Data = new File(getFilesDir(), DA3_MODEL + ".data");
        File sam = new File(getFilesDir(), SAM_MODEL);
        File samData = new File(getFilesDir(), SAM_MODEL + ".data");
        boolean ready = da3.isFile() && da3Data.isFile() && sam.isFile() && samData.isFile();
        statusView.setText(ready ? "模型已就绪，可以选择照片" : "缺少本地模型文件");
        detailView.setText(ready
                ? "所有分析均在本机完成；首次完整分析约需 1–2 分钟。"
                : "请先将 DA3 与 SAM 3 的 ONNX 图和外部权重写入应用 files 目录。");
        chooseButton.setEnabled(ready);
    }

    private void choosePhoto() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (longest > 2048) decoder.setTargetSampleSize(Math.max(1, longest / 2048));
            });
            // Keep the highest practical source sampling for SAM and rendering.
            // DA3 itself still runs at its validated fixed 1008-pixel bucket.
            sourceBitmap = ModelRunner.fitPreview(decoded, 2048);
            if (decoded != sourceBitmap) decoded.recycle();
            nmmBitmap = null;
            preview.setBitmaps(sourceBitmap, null);
            compareSlider.setEnabled(false);
            analyzeButton.setEnabled(true);
            exportButton.setEnabled(false);
            analyze();
        } catch (Throwable error) {
            showFailure("照片读取失败", error);
        }
    }

    private void analyze() {
        if (busy || sourceBitmap == null) return;
        setBusy(true);
        nmmBitmap = null;
        preview.setBitmaps(sourceBitmap, null);
        statusView.setText("准备本地推理…");
        detailView.setText("请保持应用在前台，分析过程中已锁定相关操作。");
        analysisStarted = android.os.SystemClock.elapsedRealtime();
        worker.execute(() -> {
            try {
                File input = new File(getCacheDir(), InferenceActivity.INPUT_FILE);
                try (FileOutputStream stream = new FileOutputStream(input)) {
                    if (!sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("无法准备推理图片");
                    }
                }
                runOnUiThread(() -> startInference(InferenceActivity.STAGE_SAM));
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    showFailure("分析失败", error);
                    setBusy(false);
                });
            }
        });
    }

    private void startInference(String stage) {
        if (!busy) return;
        Intent intent = new Intent(this, InferenceActivity.class);
        intent.putExtra(InferenceActivity.EXTRA_STAGE, stage);
        if (InferenceActivity.STAGE_SAM.equals(stage)) {
            statusView.setText("正在识别微缩模型");
            detailView.setText("SAM 3 / ORT CPU · 独立高精度推理进程");
        } else {
            statusView.setText("正在恢复表面深度");
            detailView.setText("DA3-LARGE-1.1 / XNNPACK · 独立 CPU 推理进程");
        }
        startActivity(intent);
    }

    private void finishRendering() {
        statusView.setText("正在生成 NMM 光影");
        detailView.setText("计算表面法线、主高光与二/三次反射");
        worker.execute(() -> {
            try {
                FloatImage mask = readArray(new File(getCacheDir(), InferenceActivity.MASK_FILE));
                FloatImage depth = readArray(new File(getCacheDir(), InferenceActivity.DEPTH_FILE));
                if (mask.width != sourceBitmap.getWidth() || mask.height != sourceBitmap.getHeight()
                        || depth.width != mask.width || depth.height != mask.height) {
                    throw new IllegalStateException("推理输出与照片尺寸不一致");
                }
                int foreground = 0;
                for (float value : mask.values) if (value > 0.5f) foreground++;
                final float coverage = foreground / (float) mask.values.length;
                Bitmap rendered = NmmRenderer.render(sourceBitmap, mask.values, depth.values);
                runOnUiThread(() -> {
                    nmmBitmap = rendered;
                    preview.setBitmaps(sourceBitmap, nmmBitmap);
                    compareSlider.setProgress(50);
                    long seconds = (android.os.SystemClock.elapsedRealtime() - analysisStarted) / 1000;
                    statusView.setText("NMM 指引已生成");
                    detailView.setText("主体覆盖 " + Math.round(coverage * 100f)
                            + "% · 总用时 " + seconds + " 秒 · 拖动滑条比较原图与光影");
                    setBusy(false);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    showFailure("光影生成失败", error);
                    setBusy(false);
                });
            }
        });
    }

    private static FloatImage readArray(File file) throws Exception {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 1024 * 1024))) {
            int width = input.readInt();
            int height = input.readInt();
            int count = input.readInt();
            if (width <= 0 || height <= 0 || count != width * height) {
                throw new IllegalStateException("推理缓存文件损坏");
            }
            float[] values = new float[count];
            for (int index = 0; index < count; index++) values[index] = input.readFloat();
            return new FloatImage(width, height, values);
        }
    }

    private static final class FloatImage {
        final int width;
        final int height;
        final float[] values;
        FloatImage(int width, int height, float[] values) {
            this.width = width;
            this.height = height;
            this.values = values;
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        chooseButton.setEnabled(!value);
        analyzeButton.setEnabled(!value && sourceBitmap != null);
        exportButton.setEnabled(!value && nmmBitmap != null);
        compareSlider.setEnabled(!value && nmmBitmap != null);
    }

    private void exportPng() {
        if (busy || nmmBitmap == null) return;
        try {
            Bitmap export = createAnnotatedExport();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = "NMM_" + stamp + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/NMM-detect");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("系统未能创建图片文件");
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null || !export.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IllegalStateException("PNG 编码失败");
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            export.recycle();
            Toast.makeText(this, "已保存到 Pictures/NMM-detect", Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            showFailure("导出失败", error);
        }
    }

    private Bitmap createAnnotatedExport() {
        int width = sourceBitmap.getWidth();
        int header = Math.max(72, width / 10);
        int height = sourceBitmap.getHeight() + header;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xff080a0e);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(Math.max(22, width / 34f));
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        canvas.drawText("NMM 光影指引", width * 0.035f, header * 0.43f, paint);
        paint.setColor(0xff8e97a8);
        paint.setTextSize(Math.max(15, width / 55f));
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText("左上主光  ·  箭头表示入射方向", width * 0.035f, header * 0.75f, paint);
        float fraction = compareSlider.getProgress() / 100f;
        canvas.drawBitmap(nmmBitmap, 0, header, paint);
        canvas.save();
        canvas.clipRect(0, header, width * fraction, height);
        canvas.drawBitmap(sourceBitmap, 0, header, paint);
        canvas.restore();
        paint.setColor(0xffd9ff52);
        paint.setStrokeWidth(Math.max(3, width / 260f));
        float divider = width * fraction;
        canvas.drawLine(divider, header, divider, height, paint);
        float sunX = width * 0.14f;
        float sunY = header + sourceBitmap.getHeight() * 0.12f;
        float endX = width * 0.43f;
        float endY = header + sourceBitmap.getHeight() * 0.37f;
        canvas.drawCircle(sunX, sunY, Math.max(8, width / 80f), paint);
        canvas.drawLine(sunX + width * 0.02f, sunY + width * 0.02f, endX, endY, paint);
        float angle = (float) Math.atan2(endY - sunY, endX - sunX);
        float arrow = Math.max(14, width / 40f);
        canvas.drawLine(endX, endY,
                endX - arrow * (float) Math.cos(angle - 0.55f),
                endY - arrow * (float) Math.sin(angle - 0.55f), paint);
        canvas.drawLine(endX, endY,
                endX - arrow * (float) Math.cos(angle + 0.55f),
                endY - arrow * (float) Math.sin(angle + 0.55f), paint);
        return out;
    }

    private void showFailure(String title, Throwable error) {
        statusView.setText(title);
        String message = error.getMessage();
        detailView.setText(message == null ? error.getClass().getSimpleName() : message);
        Toast.makeText(this, title + "：" + detailView.getText(), Toast.LENGTH_LONG).show();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(primary ? 0xff080a0e : 0xffe5e9f4);
        button.setAllCaps(false);
        button.setBackgroundColor(primary ? 0xffd9ff52 : 0xff202631);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(inferenceReceiver);
            receiverRegistered = false;
        }
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
