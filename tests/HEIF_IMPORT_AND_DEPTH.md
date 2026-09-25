# HEIF 导入与辅助深度评估（2026-09-24，0.3.4）

## 已实现

- 前端在浏览器尝试解码之前识别 HEIF/HEIC/HIF 扩展名、MIME 和 ISO BMFF `ftyp` 签名；大小写扩展名、空 MIME、通用二进制 MIME 均可走解码流程。
- Mac 使用打包应用已有的 Pillow/libheif，通过本机 `/api/import-image` 解码主图。方向由解码器和 EXIF 转正；有效 ICC 转 sRGB；输出最长边 2048 的 RGB PNG。
- Android 使用原生 `ImageDecoder` 解码 HEIF，处理方向、输出 sRGB、最长边 2048，再以 PNG 送回前端。文档选择器同时接受 image MIME 和通用二进制 MIME。
- 预览与 SAM/DA3 分析使用同一份 PNG，不再由 WebView 的 HEIF 显示能力决定能否导入，也不额外做 JPEG 有损压缩。
- 单文件最多 64 MB / 6000 万像素；损坏或系统不支持的编码会显示错误，不启动分析。PNG 不带原图的 EXIF/GPS/辅助图。用户原文件不修改、不删除，不上传外部服务；没有额外复制一份原片到应用照片库。

HEIF 是容器，系统支持依编码/配置而异；测试覆盖下列真实 HEVC HEIF 文件，不承诺任意编码、任意固件或超过限制的文件。10-bit 解码可用不等于完整保留 HDR 显示效果：当前模型仍使用 8-bit RGB/SDR 工作图，HDR gain map 不参与分析。

## 验证证据

1. 打包后的 Mac 0.3.4 原生应用，在实际 WKWebView 中把 HEIF 文件（大写 `.HEIF`、`application/octet-stream`）交给正式 `importPhoto`，完整完成 SAM + DA3；结果 `mac-import.json` 为 `passed: true`。
2. TB322FC / Android 16 安装 0.3.4，经同一个正式前端入口完成 HEIF 导入与全流程分析；随后光照/反射/细节尺度回归通过，见 `android-import.json`。
3. Mac 的测试实际请求打包应用运行的后端，不是仅测试开发 Python 环境；Android 的测试实际调用原生 bridge 并加载转换后的预览。

| 解码样本 | Mac | Android 真机 |
| --- | --- | --- |
| 相机 HEIC，带辅助深度/HDR gain map，4032×3024 | PNG 2048×1536 | PNG 2048×1536 |
| 10-bit RGB HEIF | PNG 128×128 | PNG 128×128 |
| 带旋转方向的 HEIC | 正确为 96×160 | 正确为 96×160 |
| 无 HEIF 扩展名，通用二进制 MIME，依文件签名识别 | 通过 | 通过 |
| 损坏 HEIC | 正确报错 | 正确报错 |

另有开发环境测试覆盖灰度、sRGB ICC、尺寸限制下的缩小、主图不是第一个 frame 的多图容器，以及空文件/截断文件。`tests/test_heif_import.py` 的 5 项测试通过。完整报告与截图保存在 `dist/heif-verification/`。

## 辅助深度实测

公开样本来自 `bigcat88/pillow_heif` 仓库，commit `4ce712961deece4507463c83de1a41f486d303b8`：

- [pug.heic](https://github.com/bigcat88/pillow_heif/blob/4ce712961deece4507463c83de1a41f486d303b8/tests/images/heif_other/pug.heic)
- [spatial_photo.heic](https://github.com/bigcat88/pillow_heif/blob/4ce712961deece4507463c83de1a41f486d303b8/tests/images/heif_other/spatial_photo.heic)
- [stereo_pair.heic](https://github.com/bigcat88/pillow_heif/blob/4ce712961deece4507463c83de1a41f486d303b8/tests/images/heif_other/stereo_pair.heic)

`pug.heic` 确实包含一张深度图：

- 原图 4032×3024；深度图 768×576，单通道 8-bit，实际 252 个编码值。
- 深度图横纵采样各少 5.25 倍，像素数约为原图的 3.63%；要覆盖当前 2048×1536 工作图仍需放大约 2.67 倍。
- 存在 `d_min=1.498046875`、`d_max=4.3828125`、`representation_type=1`、`disparity_reference_view=0` 等描述。这些字段必须按对应规范解释，不能把 0–255 灰度直接当成米制深度，也不能直接交给透视法线计算。
- 实际提取并查看了深度 PNG，主要描述前景/背景和较平滑的大形，不足以证明能恢复模型盔甲刻线、边缘和浅浮雕。
- 同文件另有 Apple HDR gain map；它描述亮度增益，**不是几何深度**。
- 另两个样本包含多幅/空间照片，但没有通过解码器直接提供的深度图；立体图像仍需额外的匹配、标定与遮挡处理。

提取脚本 `tests/evaluate_heif_depth.py`；原始统计 `dist/heif-verification/depth-evaluation.json`，深度图 `pug-depth-0.png`。

## 是否值得接入

**目前不建议替代 DA3，也不建议默认混入法线计算。** 现有样本只能支持“部分文件可提取辅助深度”，不能证明对微缩模型的光影有净收益；分辨率、平滑、量化、语义编码和相机标定都需要处理。Android `ImageDecoder` 的普通 Bitmap 路径也不提供这些辅助深度项，跨端接入还需要额外的容器解析/原生组件。

**作为可选的大形/前后景约束，值得下一阶段小规模验证。** 门槛是取得带辅助深度的真实微缩模型原片（同一物体、不同距离、薄边缘/金属/透明或反光情况），先正确配准并解析有效区域，再对比 DA3-only / auxiliary-only / 融合三组：主体边缘误差、细节法线、光照稳定性、耗时。只有稳定改善且能自动拒绝劣质深度时才接入。此次未启用任何辅助深度融合，避免未知质量数据影响已经修正的光影。

## 复现入口

```bash
.venv/bin/python tests/test_heif_import.py
.venv/bin/python tests/test_heif_import.py --fixture PATH/miniature.png
.venv/bin/python tests/prepare_heif_suite.py PATH/pillow_heif/tests/images
# 编译 androidTest 后安装，使用明确设备序列号
adb -s DEVICE shell am instrument -w -e importHeif true com.digitalghost.nmmprobe.test/com.digitalghost.nmmprobe.RenderInstrumentation
adb -s DEVICE shell am instrument -w -e decodeSuite true com.digitalghost.nmmprobe.test/com.digitalghost.nmmprobe.RenderInstrumentation
# 启动真正的打包 Mac 应用，生成导入 + 分析报告
"NMM Studio.app/Contents/MacOS/NMMStudio" --check-import PATH/miniature.HEIF PATH/report.json
.venv/bin/python tests/test_import_endpoint.py http://127.0.0.1:APP_PORT
.venv/bin/python tests/evaluate_heif_depth.py PATH/pillow_heif/tests/images PATH/report-directory
```
