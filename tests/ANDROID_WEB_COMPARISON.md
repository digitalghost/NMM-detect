# Android / Web 光影差异诊断（2026-09-23）

> 后续状态：下文记录 0.3.2 的诊断。0.3.3 已修复原始深度/相机内参传递、透视法线及等比输入，加入 `verify_geometry_parity.py` 验证实际 Java 与 Web 算法。历史固定增益实验保留用于复现旧问题；不代表当前生产实现。

## 结论与边界

Android 0.3.2 和当前 Web 使用相同的 `app.js`，但生成输入贴图的几何管线不等价。当前首要问题是原生法线生成，不应先以提高全局对比度掩盖它。本轮仅诊断并增加可复现对照，未更改生产渲染或安装新版本。

此前真机回归验证的是控件切换是否改变画面，不是跨端画质一致性。此次桌面对照使用 macOS WKWebView 加载当前 Web 页面，并非所有桌面浏览器。没有可确认完全相同原图的 Web 分析缓存，因此不能声称完成了同原图、两套模型端到端对照；也未测量显示屏色彩模式。

## 1. 隔离前端：完全相同的贴图

使用真机缓存 `dc61949d0247` 的 source、mask、normals、detail_normals、lineart、depth（825×1100），在当前 Web 页面重放相同按钮序列。下表为每次切换前后的平均 RGB 通道绝对差（0–255），不是两端最终图片的逐像素误差。

| 切换 | Android Chromium 137 | 桌面 WKWebView |
| --- | ---: | ---: |
| 主光 → 均衡 | 2.741856 | 2.745606 |
| 均衡 → 细节 | 1.033289 | 1.036758 |
| 反射 1 → 2 | 1.216845 | 1.216009 |
| 反射 2 → 3 | 0.566739 | 0.568664 |
| 细节尺度 2 → 4 | 0.072556 | 0.072254 |
| 细节尺度 4 → 8 | 0.159416 | 0.157922 |
| 色阶 3 → 9 | 4.669168 | 4.668079 |

两端尺度往返一致性和零反射强度测试均通过。此结果支持“主要差异在输入贴图”，但不是所有浏览器、参数、素材完全一致的证明。Android CSS 中未发现降低画布对比度的 filter/opacity。

## 2. 关键代码差异

- `backend/nmm_shader.py:depth_to_normals/depth_to_detail_normals`：使用原始浮点深度和相机内参，把像素还原为三维点，由切线叉积求法线。包含多尺度掩膜归一化高斯平滑、双边滤波及法线正则化。
- `ArtifactGenerator.java:generate`：先用主体深度的 2%/98% 分位数归一化至 0–1，截断，再用深度差乘 `max(width,height)/64`（细节为 `/92`），构造 `(-dx,-dy,1)`。起伏幅度相对于距离的信息丢失，也没有透视项。
- `scripts/export_da3_android.py` 已导出 `depth` 和 `intrinsics`，但 `ModelRunner.runDa3` 只读取第一个输出。Web 使用并随缩放、主体裁切位置调整内参。
- Android 将任意主体裁切拉伸到固定 756×1008；Web `upper_bound_resize` 等比缩放长边后适配模型尺寸。比例不同的主体会得到不同的模型输入。
- 线稿也不一致：Android 使用局部深度/照片梯度；Web 使用结构化边缘及去纹理处理。这可能影响观感，但本轮没有独立量化其贡献。

## 3. 同深度输入的受控实验

`tests/compare_geometry.py` 对相同 384×384 曲面 `z=2+a*(x²+y²)`、相同圆形遮罩比较当前 Android 公式与实际 Web 函数（Web 使用默认焦距）。Android 路径由 NumPy 等价重现，不是 Java 运行结果；统计发生在 PNG 量化及前端模糊之前。

| 起伏幅度 a | Android 倾角中位数 | Web 倾角中位数 |
| --- | ---: | ---: |
| 0.05 | 6.30° | 2.80° |
| 0.20 | 6.30° | 10.37° |
| 0.50 | 6.30° | 22.05° |

归一化使 Android 对起伏幅度变化不敏感，并非所有浅曲面都变平；对于较深曲面明显压低倾斜度。a=0.50 时，同方向光下漫反射 P10–P90 为 Android 0.757–0.878、Web 0.562–0.958。真机现有贴图的法线倾角中位数约 6.81°，与浅起伏特征相符，但没有该照片的完整浮点深度，不能从现有 PNG 精确重建正确法线。

## 建议修复顺序

1. 保留原始浮点深度，读取模型相机内参，并把内参按输入/输出缩放、裁切偏移转换到原图坐标；归一化深度仅用于可视化。
2. 将 Web 的透视法线、细节法线和平滑算法移植到原生。用平面、倾斜平面、曲面及真实深度验证法线角误差，检查 Y 轴方向、遮罩边界和有限数。
3. 对齐等比预处理。固定尺寸模型可研究等比缩放加 padding 并正确去 padding，但这不天然等价于 Web；要以模型结果验证。若仍有明显误差，再考虑多个宽高比输入桶或动态尺寸模型。
4. 对齐线稿，再做同一原图、同一遮罩、同一灯位和材质的端到端 A/B，统计主体亮度分位数、暗部/高光比例、法线角误差并人工检查。保留现有控件回归测试。
5. 已缓存的法线需要重新分析生成。不能靠前端 CSS 对比度或直接修改旧法线 PNG 恢复丢失的几何。

不建议直接把 `/64` 改成更大的固定增益，也不建议立即提高全局对比度：前者随照片深度范围变化不稳定，后者会一起压坏原图背景和高光，无法恢复正确光照位置。

## 复现

```bash
.venv/bin/python tests/compare_geometry.py
swiftc macos/RenderRegression.swift -o /tmp/nmm-render-comparison
/tmp/nmm-render-comparison "$PWD" tests/android-render-regression.js dist/android-web-comparison/android-fixture
```

桌面 harness 需要 fixture 中的 `cutout.png` 为 Android `source.png` 的副本。本次原始报告在 `dist/android-web-comparison/geometry-report.json`、`desktop-replay.json`，Android 基准在 `dist/android-0.3.2/render-regression.json`。
