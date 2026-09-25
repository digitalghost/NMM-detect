package com.digitalghost.nmmprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full offline landscape editor: Web UI plus isolated local ONNX inference. */
public final class StudioActivity extends Activity {
    private static final int PICK_FILE = 4102;
    private static final int CAPTURE_PHOTO = 4103;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String BRIDGE_INPUT = "studio-input.png";
    private static final String BRIDGE_OUTPUT = "studio-output.bin";
    private static final String RESULT_ROOT = "studio-results";
    private static final int MAX_RESULT_RUNS = 12;
    private static final long MAX_RESULT_BYTES = 512L * 1024L * 1024L;
    private static final long MODEL_INSTALL_MARGIN = 512L * 1024L * 1024L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private File pendingCaptureFile;
    private Uri pendingCaptureUri;
    private volatile Job currentJob;
    private boolean receiverRegistered;
    private boolean modelsReady;

    private final BroadcastReceiver inferenceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!InferenceActivity.ACTION_RESULT.equals(intent.getAction()) || currentJob == null) return;
            String stage = intent.getStringExtra(InferenceActivity.EXTRA_STAGE);
            String error = intent.getStringExtra(InferenceActivity.EXTRA_ERROR);
            worker.execute(() -> handleModelResult(stage, error));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(8, 8, 7));
        getWindow().setNavigationBarColor(Color.rgb(8, 8, 7));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        IntentFilter filter = new IntentFilter(InferenceActivity.ACTION_RESULT);
        registerReceiver(inferenceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(10, 10, 9));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidNMM");
        webView.setWebViewClient(new LocalClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                showPhotoSourceDialog();
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl(ORIGIN + "/assets/index.html");
        worker.execute(() -> cleanupResultCache(Set.of()));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (fileCallback == null) return;
        if (requestCode == PICK_FILE) {
            Uri selected = resultCode == RESULT_OK && data != null ? data.getData() : null;
            finishFileSelection(selected);
        } else if (requestCode == CAPTURE_PHOTO) {
            Uri captured = null;
            if (resultCode == RESULT_OK) {
                if (pendingCaptureFile != null && pendingCaptureFile.length() == 0
                        && data != null && data.getExtras() != null
                        && data.getExtras().get("data") instanceof Bitmap) {
                    try (OutputStream output = new FileOutputStream(pendingCaptureFile)) {
                        ((Bitmap) data.getExtras().get("data"))
                                .compress(Bitmap.CompressFormat.JPEG, 96, output);
                    } catch (Exception ignored) {
                        // The full-resolution EXTRA_OUTPUT path remains the primary result.
                    }
                }
                if (pendingCaptureFile != null && pendingCaptureFile.length() > 0) {
                    captured = pendingCaptureUri;
                } else if (data != null) {
                    captured = data.getData();
                }
            }
            if (captured == null && pendingCaptureFile != null) pendingCaptureFile.delete();
            if (pendingCaptureUri != null) {
                revokeUriPermission(pendingCaptureUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            pendingCaptureFile = null;
            pendingCaptureUri = null;
            finishFileSelection(captured);
        }
    }

    private void showPhotoSourceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("导入模型照片")
                .setItems(new String[]{"相机拍摄（可在相机内开启微距）", "从系统相册选择"},
                        (dialog, which) -> {
                            if (which == 0) openMacroCamera();
                            else openPhotoLibrary();
                        })
                .setNegativeButton("取消", (dialog, which) -> finishFileSelection(null))
                .setOnCancelListener(dialog -> finishFileSelection(null))
                .show();
    }

    private void openPhotoLibrary() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        // Some document/cloud providers label .heif as generic binary.
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream"});
        try {
            startActivityForResult(picker, PICK_FILE);
        } catch (Throwable error) {
            toast("无法打开系统相册");
            finishFileSelection(null);
        }
    }

    private void openMacroCamera() {
        try {
            File directory = new File(getCacheDir(), "camera-captures");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建拍照缓存目录");
            }
            deleteOldCaptures(directory);
            pendingCaptureFile = File.createTempFile("NMM_macro_", ".jpg", directory);
            pendingCaptureUri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".capture")
                    .appendPath(pendingCaptureFile.getName())
                    .build();

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
            camera.putExtra("android.intent.extras.CAMERA_FACING", 0);
            camera.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
            camera.setClipData(ClipData.newRawUri("NMM macro capture", pendingCaptureUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(
                    camera, PackageManager.MATCH_DEFAULT_ONLY);
            if (cameraApps.isEmpty()) {
                throw new IllegalStateException("没有找到可用的相机应用");
            }
            int uriFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            for (ResolveInfo cameraApp : cameraApps) {
                grantUriPermission(cameraApp.activityInfo.packageName, pendingCaptureUri, uriFlags);
            }
            startActivityForResult(camera, CAPTURE_PHOTO);
        } catch (Throwable error) {
            if (pendingCaptureUri != null) {
                revokeUriPermission(pendingCaptureUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            if (pendingCaptureFile != null) pendingCaptureFile.delete();
            pendingCaptureFile = null;
            pendingCaptureUri = null;
            toast("没有找到可用的相机应用");
            finishFileSelection(null);
        }
    }

    private static void deleteOldCaptures(File directory) {
        File[] oldCaptures = directory.listFiles((parent, name) ->
                name.startsWith("NMM_macro_") && name.endsWith(".jpg"));
        if (oldCaptures == null) return;
        for (File oldCapture : oldCaptures) oldCapture.delete();
    }

    private void finishFileSelection(Uri uri) {
        if (fileCallback == null) return;
        fileCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
        fileCallback = null;
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void decodeImport(String requestId, String dataUrl) {
            worker.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (dataUrl.length() > 90_000_000) throw new IllegalArgumentException("照片超过 64 MB");
                    byte[] bytes = decodeDataUrl(dataUrl);
                    if (bytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("照片超过 64 MB");
                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                            (decoder, info, ignored) -> {
                                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                if ((long)w*h > 60_000_000) throw new IllegalArgumentException("照片超过 6000 万像素");
                                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                                float scale = Math.min(1f, 2048f / Math.max(w,h));
                                decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
                            });
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG 转换失败");
                    callback(requestId, true, new JSONObject().put("dataUrl", "data:image/png;base64,"+
                            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)));
            } catch (Throwable error) {
                callbackError(requestId, new IllegalArgumentException("无法解码或规范化此照片："+error.getMessage()));
                } finally { if (bitmap != null) bitmap.recycle(); }
            });
        }
        @JavascriptInterface
        public void analyze(String requestId, String dataUrl, String fileName,
                            String regionsJson, String prompt) {
            worker.execute(() -> beginAnalyze(requestId, dataUrl, fileName, regionsJson, prompt));
        }

        @JavascriptInterface
        public void refine(String requestId, String analysisId, String regionJson) {
            worker.execute(() -> beginRefine(requestId, analysisId, regionJson));
        }

        @JavascriptInterface
        public void saveFile(String requestId, String dataUrl, String fileName, String mimeType) {
            worker.execute(() -> {
                try {
                    byte[] bytes = decodeDataUrl(dataUrl);
                    saveMedia(bytes, fileName, mimeType);
                    callback(requestId, true, new JSONObject().put("saved", fileName));
                    toast("已保存到 NMM-detect 相册目录");
                } catch (Throwable error) { callbackError(requestId, error); }
            });
        }

        @JavascriptInterface
        public void exportMp4(String requestId, String originalUrl, String nmmUrl, String fileName) {
            worker.execute(() -> {
                Bitmap original = null;
                Bitmap nmm = null;
                File temporary = new File(getCacheDir(), "nmm-export.mp4");
                try {
                    progress("正在本地编码 4 秒 H.264 对比视频…");
                    original = decodeBitmap(originalUrl);
                    nmm = decodeBitmap(nmmUrl);
                    if (original == null || nmm == null) throw new IllegalArgumentException("导出画面无法解码");
                    if (original.getWidth() != nmm.getWidth() || original.getHeight() != nmm.getHeight()) {
                        throw new IllegalArgumentException("原图与光影图尺寸不一致");
                    }
                    Mp4Exporter.encode(original, nmm, temporary);
                    try (InputStream input = new FileInputStream(temporary)) {
                        saveMedia(input, fileName, "video/mp4");
                    }
                    callback(requestId, true, new JSONObject().put("saved", fileName));
                    toast("微信兼容 MP4 已保存到 Movies/NMM-detect");
                } catch (Throwable error) { callbackError(requestId, error); }
                finally {
                    if (original != null) original.recycle();
                    if (nmm != null) nmm.recycle();
                    if (temporary.isFile()) temporary.delete();
                }
            });
        }
    }

    private void beginAnalyze(String requestId, String dataUrl, String fileName,
                              String regionsJson, String prompt) {
        if (currentJob != null) { callbackError(requestId, new IllegalStateException("另一个推理任务正在运行")); return; }
        try {
            ensureModels();
            Bitmap decoded = decodeBitmap(dataUrl);
            if (decoded == null) throw new IllegalArgumentException("无法解码所选照片");
            Bitmap source = ModelRunner.fitPreview(decoded, 2048);
            if (decoded != source) decoded.recycle();
            Job job = new Job(Job.TYPE_ANALYZE, requestId, newId(), source);
            job.fileName = fileName;
            job.regions = parseRegions(regionsJson);
            currentJob = job;
            if ("circle".equals(prompt)) {
                job.mask = demoMask(source.getWidth(), source.getHeight());
                progress("演示图主体已建立，正在恢复表面深度…");
                prepareAnalysisDepth(job);
            } else {
                progress("SAM 3 正在识别完整微缩模型…");
                writeBitmap(source, new File(getCacheDir(), BRIDGE_INPUT));
                startModel(InferenceActivity.STAGE_SAM, 0);
            }
        } catch (Throwable error) { finishError(requestId, error); }
    }

    private void beginRefine(String requestId, String analysisId, String regionJson) {
        if (currentJob != null) { callbackError(requestId, new IllegalStateException("另一个推理任务正在运行")); return; }
        Bitmap source = null;
        Bitmap maskBitmap = null;
        try {
            ensureModels();
            File parent = resultDir(analysisId);
            parent.setLastModified(System.currentTimeMillis());
            source = BitmapFactory.decodeFile(new File(parent, "source.png").getAbsolutePath());
            maskBitmap = BitmapFactory.decodeFile(new File(parent, "mask.png").getAbsolutePath());
            if (source == null || maskBitmap == null) throw new IllegalArgumentException("原始分析结果不存在，请重新导入照片");
            RectF normalized = parseRegion(regionJson);
            Rect box = pixelRect(normalized, source.getWidth(), source.getHeight());
            if (Math.min(box.width(), box.height()) < 32) throw new IllegalArgumentException("局部推理区域太小");
            Bitmap crop = Bitmap.createBitmap(source, box.left, box.top, box.width(), box.height());
            Bitmap cropMask = Bitmap.createBitmap(maskBitmap, box.left, box.top, box.width(), box.height());
            int outputWidth;
            int outputHeight;
            if (box.width() >= box.height()) {
                outputWidth = 1008;
                outputHeight = Math.max(64, Math.round(1008f * box.height() / box.width()));
            } else {
                outputHeight = 1008;
                outputWidth = Math.max(64, Math.round(1008f * box.width() / box.height()));
            }
            Bitmap highSource = Bitmap.createScaledBitmap(crop, outputWidth, outputHeight, true);
            Bitmap highMask = Bitmap.createScaledBitmap(cropMask, outputWidth, outputHeight, true);
            crop.recycle();
            cropMask.recycle();
            float[] mask = maskFromBitmap(highMask);
            highMask.recycle();
            int occupied = 0;
            for (float value : mask) if (value > .1f) occupied++;
            if (occupied < 64) throw new IllegalArgumentException("所选局部不包含已识别主体");
            Job job = new Job(Job.TYPE_REFINE, requestId, newId(), highSource);
            job.mask = mask;
            job.sourceAnalysisId = analysisId;
            job.sourceBox = box;
            job.sourceSize = new int[]{box.width(), box.height()};
            currentJob = job;
            writeBitmap(highSource, new File(getCacheDir(), BRIDGE_INPUT));
            progress("当前 Zoom 视口正在以 1008 px 重新计算深度…");
            startModel(InferenceActivity.STAGE_DA3, 0);
        } catch (Throwable error) {
            if (source != null) source.recycle();
            if (maskBitmap != null) maskBitmap.recycle();
            finishError(requestId, error);
            return;
        }
        source.recycle();
        maskBitmap.recycle();
    }

    private void handleModelResult(String stage, String errorText) {
        Job job = currentJob;
        if (job == null) return;
        if (errorText != null && !errorText.isBlank()) {
            if (InferenceActivity.STAGE_SAM.equals(stage) && job.mask != null && job.activeRegion != null) {
                progress("局部 SAM 未命中，正在用背景边界差异做保守增补…");
                mergeRegionFallback(job, job.activeRegion);
                continueRegions(job);
                return;
            }
            finishError(job.requestId, new IllegalStateException(errorText));
            return;
        }
        try {
            FloatImage output = readFloat(new File(getCacheDir(), BRIDGE_OUTPUT));
            if (InferenceActivity.STAGE_SAM.equals(stage)) handleSam(job, output);
            else handleDepth(job, output);
        } catch (Throwable error) { finishError(job.requestId, error); }
    }

    private void handleSam(Job job, FloatImage output) throws Exception {
        if (job.mask == null) {
            if (output.width != job.source.getWidth() || output.height != job.source.getHeight()) {
                throw new IllegalStateException("SAM 输出尺寸错误");
            }
            job.mask = output.values;
            job.regionCursor = 0;
        } else if (job.activeRegion != null) {
            mergeRegionMask(job, job.activeRegion, output);
            job.regionCursor++;
        }
        continueRegions(job);
    }

    private void continueRegions(Job job) {
        if (job.regionCursor < job.regions.size()) {
            Rect box = pixelRect(job.regions.get(job.regionCursor), job.source.getWidth(), job.source.getHeight());
            job.activeRegion = box;
            Bitmap crop = Bitmap.createBitmap(job.source, box.left, box.top, box.width(), box.height());
            try {
                writeBitmap(crop, new File(getCacheDir(), BRIDGE_INPUT));
            } catch (Throwable error) {
                crop.recycle();
                finishError(job.requestId, error);
                return;
            }
            crop.recycle();
            progress(String.format(Locale.CHINA, "正在增补识别区域 %d / %d…", job.regionCursor + 1, job.regions.size()));
            startModel(InferenceActivity.STAGE_SAM, 1450);
        } else {
            job.activeRegion = null;
            prepareAnalysisDepth(job);
        }
    }

    private void prepareAnalysisDepth(Job job) {
        try {
            job.focusBox = focusBox(job.mask, job.source.getWidth(), job.source.getHeight());
            Bitmap focused = Bitmap.createBitmap(job.source, job.focusBox.left, job.focusBox.top,
                    job.focusBox.width(), job.focusBox.height());
            writeBitmap(focused, new File(getCacheDir(), BRIDGE_INPUT));
            focused.recycle();
            progress("DA3-LARGE-1.1 正在进行主体聚焦深度估算…");
            startModel(InferenceActivity.STAGE_DA3, 1450);
        } catch (Throwable error) { finishError(job.requestId, error); }
    }

    private void handleDepth(Job job, FloatImage output) throws Exception {
        progress("正在生成法线、微结构法线与纯线稿…");
        if (output.camera == null) throw new IllegalStateException("深度缓存缺少相机内参，请重新分析照片");
        float[] camera = output.camera.clone();
        float[] depth;
        if (job.type == Job.TYPE_ANALYZE) {
            Rect focus = job.focusBox;
            camera[2] += focus.left;
            camera[5] += focus.top;
            if (output.width != focus.width() || output.height != focus.height()) {
                throw new IllegalStateException("主体聚焦深度尺寸错误");
            }
            depth = new float[job.source.getWidth() * job.source.getHeight()];
            for (int y = 0; y < focus.height(); y++) {
                System.arraycopy(output.values, y * focus.width(), depth,
                        (focus.top + y) * job.source.getWidth() + focus.left, focus.width());
            }
        } else {
            if (output.width != job.source.getWidth() || output.height != job.source.getHeight()) {
                throw new IllegalStateException("局部深度尺寸错误");
            }
            depth = output.values;
        }
        File directory = resultDir(job.id);
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法建立分析缓存目录");
        ArtifactGenerator.Result artifacts = ArtifactGenerator.generate(job.source, job.mask, depth, camera);
        try {
            writeBitmap(job.source, new File(directory, "source.png"));
            writeBitmap(job.source, new File(directory, "crop.png"));
            writeBitmap(artifacts.mask, new File(directory, "mask.png"));
            writeBitmap(artifacts.depth, new File(directory, "depth.png"));
            writeBitmap(artifacts.normals, new File(directory, "normals.png"));
            writeBitmap(artifacts.detailNormals, new File(directory, "detail_normals.png"));
        writeBitmap(artifacts.lineart, new File(directory, "lineart.png"));
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                try (java.io.DataOutputStream raw = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(new File(directory, "geometry-v2.bin"))))) {
                    raw.writeInt(job.source.getWidth()); raw.writeInt(job.source.getHeight());
                    for (float value : camera) raw.writeFloat(value);
                    for (float value : depth) raw.writeFloat(value);
                    for (float value : job.mask) raw.writeFloat(value);
                }
            }
        } finally { artifacts.recycle(); }

        JSONObject payload = new JSONObject();
        payload.put("id", job.id);
        payload.put("artifacts", artifactUrls(job.id));
        if (job.type == Job.TYPE_ANALYZE) {
            float gain = Math.max(job.source.getWidth(), job.source.getHeight())
                    / (float) Math.max(job.focusBox.width(), job.focusBox.height());
            JSONObject precision = new JSONObject();
            precision.put("mode", gain >= 1.08f ? "subject_focus" : "full_frame");
            precision.put("effective_gain", gain);
            precision.put("source_size", new JSONArray(new int[]{job.source.getWidth(), job.source.getHeight()}));
            precision.put("crop_size", new JSONArray(new int[]{job.focusBox.width(), job.focusBox.height()}));
            payload.put("depth_precision", precision);
        } else {
            JSONObject refinement = new JSONObject();
            refinement.put("source_box", new JSONArray(new int[]{job.sourceBox.left, job.sourceBox.top,
                    job.sourceBox.right, job.sourceBox.bottom}));
            refinement.put("source_size", new JSONArray(job.sourceSize));
            refinement.put("output_size", new JSONArray(new int[]{job.source.getWidth(), job.source.getHeight()}));
            refinement.put("linear_gain", Math.min(job.source.getWidth() / (float) job.sourceSize[0],
                    job.source.getHeight() / (float) job.sourceSize[1]));
            payload.put("refinement", refinement);
        }
        callback(job.requestId, true, payload);
        cleanupResultCache(job.sourceAnalysisId == null
                ? Set.of(job.id) : Set.of(job.id, job.sourceAnalysisId));
        recycleJob(job);
        currentJob = null;
    }

    private void mergeRegionMask(Job job, Rect box, FloatImage local) {
        int width = job.source.getWidth();
        for (int y = 0; y < box.height(); y++) {
            int sy = Math.min(local.height - 1, Math.round(y * (local.height - 1f) / Math.max(1, box.height() - 1)));
            for (int x = 0; x < box.width(); x++) {
                int sx = Math.min(local.width - 1, Math.round(x * (local.width - 1f) / Math.max(1, box.width() - 1)));
                float value = local.values[sy * local.width + sx];
                int target = (box.top + y) * width + box.left + x;
                job.mask[target] = Math.max(job.mask[target], value);
            }
        }
    }

    /** Conservative non-AI fallback: pixels unlike the selected rectangle border. */
    private void mergeRegionFallback(Job job, Rect box) {
        int width = job.source.getWidth();
        int[] pixels = new int[box.width() * box.height()];
        job.source.getPixels(pixels, 0, box.width(), box.left, box.top, box.width(), box.height());
        long red = 0, green = 0, blue = 0;
        int samples = 0;
        for (int x = 0; x < box.width(); x += Math.max(1, box.width() / 40)) {
            int top = pixels[x];
            int bottom = pixels[(box.height() - 1) * box.width() + x];
            red += Color.red(top) + Color.red(bottom); green += Color.green(top) + Color.green(bottom);
            blue += Color.blue(top) + Color.blue(bottom); samples += 2;
        }
        for (int y = 1; y < box.height() - 1; y += Math.max(1, box.height() / 40)) {
            int left = pixels[y * box.width()];
            int right = pixels[y * box.width() + box.width() - 1];
            red += Color.red(left) + Color.red(right); green += Color.green(left) + Color.green(right);
            blue += Color.blue(left) + Color.blue(right); samples += 2;
        }
        float br = red / (float) Math.max(1, samples);
        float bg = green / (float) Math.max(1, samples);
        float bb = blue / (float) Math.max(1, samples);
        for (int y = 0; y < box.height(); y++) {
            for (int x = 0; x < box.width(); x++) {
                int color = pixels[y * box.width() + x];
                float distance = (float) Math.sqrt(square(Color.red(color) - br)
                        + square(Color.green(color) - bg) + square(Color.blue(color) - bb));
                float edgeDistance = Math.min(Math.min(x, box.width() - 1 - x),
                        Math.min(y, box.height() - 1 - y));
                float confidence = clamp((distance - 26f) / 58f) * clamp(edgeDistance / 5f);
                int target = (box.top + y) * width + box.left + x;
                job.mask[target] = Math.max(job.mask[target], confidence);
            }
        }
        job.regionCursor++;
    }

    private void startModel(String stage, long delayMillis) {
        runOnUiThread(() -> webView.postDelayed(() -> {
            if (currentJob == null) return;
            Intent intent = new Intent(this, InferenceActivity.class);
            intent.putExtra(InferenceActivity.EXTRA_STAGE, stage);
            intent.putExtra(InferenceActivity.EXTRA_INPUT_FILE, BRIDGE_INPUT);
            intent.putExtra(InferenceActivity.EXTRA_OUTPUT_FILE, BRIDGE_OUTPUT);
            startActivity(intent);
        }, delayMillis));
    }

    private JSONObject artifactUrls(String id) throws Exception {
        JSONObject urls = new JSONObject();
        for (String name : new String[]{"crop", "mask", "depth", "normals", "detail_normals", "lineart"}) {
            urls.put(name, ORIGIN + "/generated/" + id + "/" + name + ".png");
        }
        return urls;
    }

    private void progress(String message) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.__androidNMMProgress&&window.__androidNMMProgress(" + JSONObject.quote(message) + ")", null));
    }

    private void callback(String requestId, boolean succeeded, JSONObject payload) {
        String script = "window.__androidNMMCallback(" + JSONObject.quote(requestId) + ","
                + succeeded + "," + JSONObject.quote(payload.toString()) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void callbackError(String requestId, Throwable error) {
        try {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            callback(requestId, false, new JSONObject().put("detail", message));
        } catch (Throwable ignored) { }
    }

    private void finishError(String requestId, Throwable error) {
        Job job = currentJob;
        callbackError(requestId, error);
        if (job != null) {
            File incomplete = resultDir(job.id);
            if (incomplete.isDirectory()) deleteTree(incomplete);
            recycleJob(job);
        }
        currentJob = null;
    }

    private void recycleJob(Job job) {
        if (job.source != null && !job.source.isRecycled()) job.source.recycle();
    }

    private synchronized void ensureModels() throws Exception {
        if (modelsReady) return;
        String manifestText;
        try (InputStream input = getAssets().open("models/manifest.tsv")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            manifestText = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        Map<String, ModelAsset> assets = new LinkedHashMap<>();
        for (String line : manifestText.split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t");
            if (fields.length != 3 || !fields[0].matches("[a-z0-9.-]+")) {
                throw new IllegalStateException("内置模型清单损坏");
            }
            assets.put(fields[0], new ModelAsset(
                    fields[0], Long.parseLong(fields[1]), fields[2]));
        }
        if (assets.size() != 4) throw new IllegalStateException("内置模型清单不完整");

        File marker = new File(getFilesDir(), ".bundled-models.tsv");
        if (marker.isFile()
                && readUtf8(marker).equals(manifestText)
                && assets.values().stream().allMatch(asset -> asset.matchesSize(getFilesDir()))) {
            modelsReady = true;
            return;
        }

        List<ModelAsset> pendingAssets = new ArrayList<>();
        long requiredBytes = MODEL_INSTALL_MARGIN;
        for (ModelAsset asset : assets.values()) {
            File destination = new File(getFilesDir(), asset.name);
            if (asset.matchesSize(getFilesDir()) && asset.sha256.equals(sha256(destination))) continue;
            pendingAssets.add(asset);
            requiredBytes = Math.addExact(requiredBytes, asset.size);
        }

        if (!pendingAssets.isEmpty() && getFilesDir().getUsableSpace() < requiredBytes) {
            long requiredMegabytes = Math.max(1, (requiredBytes + 1024 * 1024 - 1) / (1024 * 1024));
            throw new IllegalStateException("存储空间不足；安装内置本地模型至少还需要 "
                    + requiredMegabytes + " MB 可用空间");
        }

        int index = 0;
        for (ModelAsset asset : pendingAssets) {
            index++;
            File destination = new File(getFilesDir(), asset.name);
            progress("正在释放内置 AI 模型 " + index + "/" + pendingAssets.size() + " · 请保持应用开启…");
            installBundledModel(asset, destination);
        }

        File markerPart = new File(getFilesDir(), ".bundled-models.tsv.part");
        writeUtf8(markerPart, manifestText);
        moveAtomically(markerPart, marker);
        modelsReady = true;
    }

    private void installBundledModel(ModelAsset asset, File destination) throws Exception {
        File temporary = new File(getFilesDir(), asset.name + ".installing");
        if (temporary.isFile() && !temporary.delete()) {
            throw new IllegalStateException("无法清理未完成的模型安装：" + asset.name);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        try (InputStream input = new BufferedInputStream(
                getAssets().open("models/" + asset.name,
                        android.content.res.AssetManager.ACCESS_STREAMING),
                4 * 1024 * 1024);
             FileOutputStream fileOutput = new FileOutputStream(temporary);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput, 4 * 1024 * 1024)) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                written += count;
            }
            output.flush();
            fileOutput.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }
        if (written != asset.size || !asset.sha256.equals(hex(digest.digest()))) {
            temporary.delete();
            throw new IllegalStateException("内置模型校验失败：" + asset.name);
        }
        moveAtomically(temporary, destination);
    }

    private static void moveAtomically(File source, File destination) throws Exception {
        try {
            Files.move(source.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), 4 * 1024 * 1024)) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static String readUtf8(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeUtf8(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("无效的数据 URL");
        return Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
    }

    private static Bitmap decodeBitmap(String dataUrl) throws Exception {
        byte[] bytes = decodeDataUrl(dataUrl);
        ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
    }

    private static void writeBitmap(Bitmap bitmap, File file) throws Exception {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG 编码失败");
            }
        }
    }

    private static FloatImage readFloat(File file) throws Exception {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file), 1024 * 1024))) {
            int width = input.readInt();
            int height = input.readInt();
            int count = input.readInt();
            if (width <= 0 || height <= 0 || count != width * height) {
                throw new IllegalStateException("推理缓存文件损坏");
            }
            float[] values = new float[count];
            for (int i = 0; i < count; i++) values[i] = input.readFloat();
            float[] camera = null;
            long extra = file.length() - 12L - count * 4L;
            if (extra != 0 && extra != 36) throw new IllegalStateException("推理相机缓存损坏");
            if (extra == 36) { camera = new float[9]; for (int i=0;i<9;i++) camera[i]=input.readFloat(); }
            return new FloatImage(width, height, values, camera);
        }
    }

    private static float[] maskFromBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        float[] mask = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) mask[i] = Color.red(pixels[i]) / 255f;
        return mask;
    }

    private static float[] demoMask(int width, int height) {
        float[] mask = new float[width * height];
        float cx = width * .5f, cy = height * .52f, radius = Math.min(width, height) * .34f;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float distance = (float) Math.hypot(x - cx, y - cy);
            mask[y * width + x] = clamp((radius + 2f - distance) / 4f);
        }
        return mask;
    }

    private static List<RectF> parseRegions(String json) throws Exception {
        JSONArray array = new JSONArray(json == null ? "[]" : json);
        if (array.length() > 8) throw new IllegalArgumentException("补充区域最多 8 个");
        List<RectF> regions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONArray values = array.getJSONArray(i);
            RectF region = new RectF((float) values.getDouble(0), (float) values.getDouble(1),
                    (float) values.getDouble(2), (float) values.getDouble(3));
            validateRegion(region);
            regions.add(region);
        }
        return regions;
    }

    private static RectF parseRegion(String json) throws Exception {
        JSONArray values = new JSONArray(json);
        RectF region = new RectF((float) values.getDouble(0), (float) values.getDouble(1),
                (float) values.getDouble(2), (float) values.getDouble(3));
        validateRegion(region);
        return region;
    }

    private static void validateRegion(RectF region) {
        if (region.left < 0 || region.top < 0 || region.right > 1 || region.bottom > 1
                || region.left >= region.right || region.top >= region.bottom) {
            throw new IllegalArgumentException("识别区域坐标必须位于 0–1");
        }
    }

    private static Rect pixelRect(RectF region, int width, int height) {
        int left = Math.max(0, Math.min(width - 1, Math.round(region.left * width)));
        int top = Math.max(0, Math.min(height - 1, Math.round(region.top * height)));
        int right = Math.max(left + 1, Math.min(width, Math.round(region.right * width)));
        int bottom = Math.max(top + 1, Math.min(height, Math.round(region.bottom * height)));
        return new Rect(left, top, right, bottom);
    }

    private static Rect focusBox(float[] mask, int width, int height) {
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (mask[y * width + x] > .18f) {
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) throw new IllegalArgumentException("没有识别到可用主体");
        int subjectWidth = maxX - minX + 1;
        int subjectHeight = maxY - minY + 1;
        int padX = Math.max(24, Math.round(subjectWidth * .14f));
        int padY = Math.max(24, Math.round(subjectHeight * .14f));
        Rect result = new Rect(Math.max(0, minX - padX), Math.max(0, minY - padY),
                Math.min(width, maxX + padX + 1), Math.min(height, maxY + padY + 1));
        float gain = Math.max(width, height) / (float) Math.max(result.width(), result.height());
        return gain < 1.08f ? new Rect(0, 0, width, height) : result;
    }

    private static float square(float value) { return value * value; }
    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private File resultDir(String id) {
        if (id == null || !id.matches("[0-9a-f]{12}")) return new File(getCacheDir(), "invalid");
        return new File(new File(getCacheDir(), RESULT_ROOT), id);
    }

    private void cleanupResultCache(Set<String> protectedIds) {
        File root = new File(getCacheDir(), RESULT_ROOT);
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        long keptBytes = 0;
        int keptRuns = 0;
        for (File directory : directories) {
            long size = directorySize(directory);
            boolean keep = protectedIds.contains(directory.getName())
                    || (keptRuns < MAX_RESULT_RUNS && keptBytes + size <= MAX_RESULT_BYTES);
            if (keep) {
                keptRuns++;
                keptBytes += size;
            } else {
                deleteTree(directory);
            }
        }
    }

    private static long directorySize(File path) {
        if (path.isFile()) return path.length();
        long total = 0;
        File[] children = path.listFiles();
        if (children != null) for (File child : children) total += directorySize(child);
        return total;
    }

    private static void deleteTree(File path) {
        File[] children = path.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!path.delete()) android.util.Log.w("NMMAndroid", "Unable to delete cache path: " + path);
    }

    private void saveMedia(byte[] bytes, String fileName, String mimeType) throws Exception {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) { saveMedia(input, fileName, mimeType); }
    }

    private void saveMedia(InputStream input, String fileName, String mimeType) throws Exception {
        boolean video = mimeType.startsWith("video/");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/NMM-detect");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) throw new IllegalStateException("系统无法创建导出文件");
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("系统无法写入导出文件");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        } catch (Throwable error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, ready, null, null);
    }

    private void toast(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()); }

    private final class LocalClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return !"https".equals(uri.getScheme())
                    || !"appassets.androidplatform.net".equals(uri.getHost());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"appassets.androidplatform.net".equals(uri.getHost())) return null;
            String path = uri.getPath();
            try {
                if (path != null && path.startsWith("/assets/")) {
                    String name = path.substring("/assets/".length());
                    if (name.startsWith("models/")) return null;
                    return response(mime(name), getAssets().open(name));
                }
                if (path != null && path.startsWith("/generated/")) {
                    String relative = path.substring("/generated/".length());
                    File root = new File(getCacheDir(), RESULT_ROOT).getCanonicalFile();
                    File file = new File(root, relative).getCanonicalFile();
                    if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) return null;
                    return response(mime(file.getName()), new FileInputStream(file));
                }
            } catch (Throwable error) {
                android.util.Log.e("NMMAndroid", "Local resource failed: " + path, error);
            }
            return null;
        }

        private WebResourceResponse response(String mime, InputStream stream) {
            WebResourceResponse response = new WebResourceResponse(mime, "UTF-8", stream);
            response.setResponseHeaders(java.util.Map.of(
                    "Cache-Control", "no-store",
                    "Access-Control-Allow-Origin", ORIGIN,
                    "X-Content-Type-Options", "nosniff",
                    "Content-Security-Policy",
                    "default-src 'self'; img-src 'self' blob: data:; "
                            + "style-src 'self' 'unsafe-inline'; script-src 'self'; "
                            + "connect-src 'self' data:; media-src 'self' blob:; "
                            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"));
            return response;
        }

        private String mime(String name) {
            if (name.endsWith(".js")) return "application/javascript";
            if (name.endsWith(".css")) return "text/css";
            if (name.endsWith(".html")) return "text/html";
            String extension = MimeTypeMap.getFileExtensionFromUrl(name);
            String detected = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            return detected == null ? "application/octet-stream" : detected;
        }
    }

    private static final class FloatImage {
        final int width;
        final int height;
        final float[] values;
        final float[] camera;
        FloatImage(int width, int height, float[] values, float[] camera) {
            this.width = width; this.height = height; this.values = values; this.camera = camera;
        }
    }

    private static final class ModelAsset {
        final String name;
        final long size;
        final String sha256;

        ModelAsset(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }

        boolean matchesSize(File root) {
            File file = new File(root, name);
            return file.isFile() && file.length() == size;
        }
    }

    private static final class Job {
        static final int TYPE_ANALYZE = 1;
        static final int TYPE_REFINE = 2;
        final int type;
        final String requestId;
        final String id;
        final Bitmap source;
        String fileName;
        float[] mask;
        List<RectF> regions = List.of();
        int regionCursor = -1;
        Rect activeRegion;
        Rect focusBox;
        Rect sourceBox;
        int[] sourceSize;
        String sourceAnalysisId;

        Job(int type, String requestId, String id, Bitmap source) {
            this.type = type; this.requestId = requestId; this.id = id; this.source = source;
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(inferenceReceiver);
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (pendingCaptureFile != null && pendingCaptureFile.length() == 0) {
            pendingCaptureFile.delete();
        }
        worker.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidNMM");
            webView.destroy();
        }
        super.onDestroy();
    }
}
