# NMM Android 全功能本地版

## 0.3.5 模型随 APK 分发与资源治理

四个 SAM 3 / DA3 ONNX 文件现在会在构建时写入 APK 的 `assets/models/`，并生成包含文件大小和 SHA-256 的清单。首次分析时，应用会把模型流式释放到私有 `files/` 目录，校验后原子替换；升级时会复用哈希一致的已有文件，不再需要通过 ADB 手工推送模型。

所有照片格式都会先规范化为最长边 2048 px 的 sRGB PNG，避免高像素 JPEG/PNG 直接创建多组超大 WebView Canvas。分析缓存限制为最近 12 次或 512 MB，发布版不再写入仅供诊断的浮点几何文件。

## 0.3.4 HEIF / HEIC 导入

HEIF 先由 Android 系统原生解码为 sRGB PNG，再预览和自动分析，不再依赖 WebView 直接显示 HEIF。支持文件扩展名/MIME/文件头识别，原文件保持不变。单文件限制 64 MB、6000 万像素，工作图最长边 2048；具体编码能力取决于设备系统。

TB322FC 真机已验证完整 HEIF 分析，以及相机 HEIC、10-bit HEIF、旋转方向、通用二进制 MIME 和损坏文件处理。辅助深度暂不参与光影计算，详见 `tests/HEIF_IMPORT_AND_DEPTH.md`。

## 0.3.3 光影几何修复

- 主法线和细节法线改为原始浮点深度 + DA3 相机内参的透视重建；移植 Web 的多尺度掩膜高斯平滑、保边双边滤波和法线正则化。
- 固定尺寸 ONNX 输入改为等比缩放、均值色留边，输出深度去留边；焦距/主点跟随缩放和主体裁切偏移调整。放大时不混入留边像素。
- Debug 构建的新分析缓存保存 `geometry-v2.bin`：大端 int32 宽/高、9 个 float32 内参、宽×高浮点深度、宽×高浮点遮罩，用于可重复的跨端验证；Release 构建不写入该诊断文件。
- **已有照片需要重新分析。** 不删除旧照片、模型或分析缓存。

验证生产 Java 算法与实际 Web NumPy/OpenCV 实现（需要现有 JDK 17 和 Python 虚拟环境）：

```bash
.venv/bin/python tests/verify_geometry_parity.py
# 使用从真机取回的 geometry-v2.bin；同目录有 normals.png/detail_normals.png 时也检查实际 PNG。
.venv/bin/python tests/verify_geometry_parity.py --geometry PATH/geometry-v2.bin
```

在下面的 instrumentation 命令中加入 `-e fresh true` 可从缓存原图重新执行 SAM + DA3；`-e fresh refine` 验证横向局部放大。可用 `-e fixture ANALYSIS_ID` 指定缓存，默认最新完整缓存。真机新分析照片的主/细节法线与 Web 参考实现平均角误差约 0.002°/0.005°。

范围说明：对齐的是几何/法线算法。固定尺寸模型的等比留边并不等价于 Web 可变宽高输入；原生线稿仍是轻量实现。不同推理后端、遮罩及线稿仍可能带来残余差异，不承诺所有照片逐像素相同。

## 渲染回归测试（0.3.2）

修正补光被主光覆盖、二/三次反射贡献被阈值截断，以及 WebView 不支持 Canvas 模糊导致细节尺度失效的问题。模糊使用共享的 CPU 实现。

真机至少完成一次照片分析后，可使用其本地缓存照片测试真实 WebView 按钮：

```bash
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w com.digitalghost.nmmprobe.test/com.digitalghost.nmmprobe.RenderInstrumentation
adb -s DEVICE_SERIAL shell run-as com.digitalghost.nmmprobe cat cache/render-regression.json
```

运行时保持设备解锁。测试覆盖三档光照、三档反射、细节尺度 2/4/8、色阶、细节尺度往返一致性，以及反射强度为零时各反射档位的一致性。报告须为 `passed: true`；失败返回 `INSTRUMENTATION_CODE: 0`，成功为 `-1`。屏幕截图保存在应用缓存 `render-regression.png`。测试会暂时切换编辑器内容，不删除照片或模型。

这是 NMM-detect 的离线 Android 实现。应用固定为横屏，用 WebView 复用桌面版的完整画布渲染和交互，再通过 JavaScript Bridge 调用 Android 本地的 SAM 3 与 DA3-LARGE-1.1 ONNX 模型。照片和推理结果不会离开设备。

## 已实现功能

- 导入照片后自动进行主体识别和深度估算；
- 框选漏识别部位，裁切放大后再次运行 SAM 3，并把结果增补到主体；
- 主体聚焦深度估算、表面法线、细节法线、线稿和表面分区；
- Android 双指焦点缩放：两触点构成矩形对角，松手后把该矩形作为视口进行 1008 px 局部 DA3 重算；双指收拢退回整体视图；
- 原图、NMM 光影、中央擦拭对比和触摸检查镜；
- 主光拖动、自动补光、细节光、艺术方向预设、光滑度、平滑度、色阶、环境反射及一至三次反射；
- 白银、不锈钢、铝、黄金、红铜、青铜、黑钢与七种光谱色调组合；
- 带光源位置和入射箭头的对照 PNG、当前画面 PNG；
- 微信和 iPhone 可预览的 4 秒 H.264 MP4 擦拭对比视频；
- 推理期间锁定相关控件，左右工具区和预览区互不撑高。

Android UI 的源文件仍是仓库根目录的 `index.html`、`styles.css` 和 `app.js`。Gradle 的 `syncWebAssets` 任务会在每次构建前自动复制它们；`src/main/assets/android.css` 只负责横屏设备上的紧凑布局，不删除桌面版功能。

## 本地模型

构建输入位于 `android-probe/models/`：

- `sam3-miniature-1008.onnx`
- `sam3-miniature-1008.onnx.data`
- `da3-large-1008x756.onnx`
- `da3-large-1008x756.onnx.data`

如果目录为空，先在仓库根目录准备桌面模型并导出 Android 图：

```bash
.venv/bin/python scripts/download_models.py
.venv/bin/python scripts/export_sam3_android.py
.venv/bin/python scripts/export_da3_android.py
```

Gradle 会校验这四个文件、生成清单并打入 APK。模型约 3.4 GB，因此 APK 约为 3.5 GB。安装 APK 和首次释放私有模型会同时占用空间，设备上建议至少预留 8 GB 可用空间。安装完成后推理不依赖项目目录、网络或额外模型文件。

SAM 3 使用标准 ONNX Runtime CPU。实测 XNNPACK 会改变候选排序，把正确的 query 157 错排成底座 query 11，因此不要为 SAM 启用 XNNPACK。DA3 使用 XNNPACK CPU。

两个模型在透明、短生命周期的独立进程中严格串行运行。每一阶段完成后都会终止相应进程，以确定性释放约 6–7 GB 的原生临时内存。

## 构建

需要 JDK 17、Android SDK 和 Gradle 8.7。ONNX Runtime 1.30.0 由 Maven Central 固定解析，无需手工复制 AAR：

```bash
cd android-probe
./gradlew assembleDebug
```

APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 真机验证基线

- 设备：Android 16、Qualcomm SM8750P、Adreno 830、12 GB RAM；
- 测试照片：Web 端同一张 `1280 × 1706` 微缩模型照片；
- SAM 3：正确选择 query 157，分数约 `0.780`；
- DA3-LARGE-1.1：完整完成全图与 Zoom 局部深度推理；
- 主体聚焦采样密度：该测试图约 `×1.39`；
- CPU 端到端耗时：约 1–2 分钟，取决于照片和主体裁切大小；
- 峰值内存：独立推理进程约 6–7 GB；
- 导出目录：图片在 `Pictures/NMM-detect`，视频在 `Movies/NMM-detect`。

当前版本刻意不启用 Vulkan/GPU：这台设备上的 DA3 Vulkan 图曾导致进程闪退。CPU 路径是已验证且与现有稳定模型权重一致的实现。
