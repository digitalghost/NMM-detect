# NMM Android 全功能本地版

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

为避免 APK 超过常规分发大小，权重不打包进 APK。应用私有 `files/` 目录必须包含：

- `sam3-miniature-1008.onnx`
- `sam3-miniature-1008.onnx.data`
- `da3-large-1008x756.onnx`
- `da3-large-1008x756.onnx.data`

SAM 3 使用标准 ONNX Runtime CPU。实测 XNNPACK 会改变候选排序，把正确的 query 157 错排成底座 query 11，因此不要为 SAM 启用 XNNPACK。DA3 使用 XNNPACK CPU。

两个模型在透明、短生命周期的独立进程中严格串行运行。每一阶段完成后都会终止相应进程，以确定性释放约 6–7 GB 的原生临时内存。

## 构建

需要 JDK 17、Android SDK 和 Gradle 8.7。经过验证的 `onnxruntime-android-1.30.0.aar` 放在 `app/libs/` 后执行：

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
