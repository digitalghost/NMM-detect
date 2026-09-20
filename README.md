# NMM-detect

> 把一张微缩模型照片，变成可直接参考的 NMM（Non-Metallic Metal，非金属金属）光影指引。

[![GitHub stars](https://img.shields.io/github/stars/digitalghost/NMM-detect?style=flat-square)](https://github.com/digitalghost/NMM-detect/stargazers)
[![GitHub last commit](https://img.shields.io/github/last-commit/digitalghost/NMM-detect?style=flat-square)](https://github.com/digitalghost/NMM-detect/commits/main)
![Local first](https://img.shields.io/badge/inference-local--first-58c9ff?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.12-3776ab?style=flat-square&logo=python&logoColor=white)
![Apple Silicon](https://img.shields.io/badge/verified-Apple%20Silicon-111827?style=flat-square&logo=apple)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-d9ff43?style=flat-square)](LICENSE)

**NMM-detect** 是一个面向微缩模型、兵人和手办涂装者的本地 NMM 预演工具。导入单张照片后，它会自动识别主体、估算深度与表面法线，再使用可解释的固定渲染算法生成分层金属高光。你可以拖动光源、放大局部、切换材质，并导出带有光源位置和入射方向的 PNG 或微信友好的 MP4 参考图。

它不是“一键替你画完”的生成式绘画模型，而是一张可调整、可对照、可落笔的涂装地图。

**English:** NMM-detect is a local-first lighting study tool for miniature painters. It combines local subject segmentation and monocular geometry estimation with a deterministic NMM renderer, producing adjustable, annotated painting references from a single photo. See [English overview](#english-overview) below.

## 为什么做这个项目

NMM 并不只是还原真实金属反射。它需要画师主动安排明暗节奏，让装甲、武器、铆钉和甲片接缝即使在固定观看角度下也能保持清晰、锐利并且“像金属”。

传统做法通常依赖经验、反复试色或复杂的 3D 建模。NMM-detect 尝试提供一条更直接的路径：

1. 拍摄真实模型；
2. 从照片恢复足够用于涂装判断的形体信息；
3. 用稳定、可重复的算法安排主高光、补光和反射层次；
4. 输出一张能放在手机旁边照着画的参考图。

## 当前能力

- **自动主体分割**：使用本地 SAM 3 找出照片中的微缩模型。
- **漏识别区域补选**：在桶、武器、披风等遗漏部位框选矩形，重新识别并合并进主体。
- **单目深度与法线估算**：使用 Depth Anything 3 在主体区域进行最高 `1008 px` 推理。
- **局部高精度推理**：放大当前视口后重新裁切、放大和估算，提升小零件的有效采样密度。
- **线稿提取**：从主体轮廓、深度和局部曲率生成白底结构线稿，减少原始颜色与质感干扰。
- **固定算法 NMM 渲染**：AI 只负责识别和几何估算；最终光影由确定性的法线、材质与光照算法生成。
- **艺术化多光源**：主光、自动补光、细节光和环境反光协同工作，不强求完全物理真实，优先服务涂装可读性。
- **一至三次反射层次**：主反射色阶、材质冷调二次反射、材质暖调三次反射可分别呈现。
- **材质与色调预设**：白银、不锈钢、铝、黄金、红铜、青铜、黑钢，搭配原色、红、黄、绿、蓝、紫、黑色调。
- **金属质感控制**：调整光影强度、金属光滑度、法线平滑度、色阶数量和微结构光照。
- **快速对照**：原图、拖动分隔线对比、完整光影三种模式无需重复推理。
- **局部检查镜**：桌面端按住 `Alt/Option` 查看局部前后效果；触摸设备可长按查看。
- **离线导出**：生成高清参考板 PNG、当前画面 PNG，以及 H.264 微信对比视频。
- **光源说明**：所有导出成品都会标出 `L1` 主光源位置、方位和光线入射方向。

## 工作原理

```mermaid
flowchart LR
    A[单张模型照片] --> B[SAM 3 主体分割]
    B --> C[Depth Anything 3 深度估算]
    C --> D[表面法线与微结构]
    B --> E[结构线稿]
    D --> F[固定算法 NMM 渲染]
    E --> F
    F --> G[原图 / 对比 / 光影]
    G --> H[PNG 参考板]
    G --> I[微信 MP4]
```

这条工作流有意把“视觉理解”和“最终上色”分开：

- **视觉模型**负责主体、深度和形体线索；
- **渲染器**负责可重复的光照、材质、色阶与反射规则；
- **用户**负责决定艺术方向并把参考转化为真实涂装。

因此，同一组参数始终会得到一致的结果，也不需要为每次 NMM 生成调用云端大模型。

## 运行要求

当前版本主要在以下环境验证：

- macOS + Apple Silicon；
- Python `3.12`；
- 建议 `24 GB` 统一内存；
- 约 `8 GB` 模型权重存储空间，另需为 Python 环境和推理输出预留空间；
- 推荐安装 [uv](https://docs.astral.sh/uv/)；
- MP4 导出推荐安装 FFmpeg，程序也会尝试使用 `imageio-ffmpeg` 提供的编码器。

CUDA/Linux 理论上可以通过 PyTorch 运行，但目前不是已完整验证的平台。纯 CPU 可以作为兼容回退，速度会明显降低。

## 快速开始

### 1. 克隆仓库

```bash
git clone https://github.com/digitalghost/NMM-detect.git
cd NMM-detect
```

### 2. 创建环境并安装依赖

```bash
uv sync --python 3.12
```

Depth Anything 3 当前需要单独安装固定版本：

```bash
UV_CACHE_DIR=/tmp/nmm-detect-uv-cache \
uv pip install --python .venv/bin/python --no-deps \
'git+https://github.com/ByteDance-Seed/depth-anything-3.git@3d835ec1a5802d64a8b8b15f817a1ab54809bfe4'
```

在 macOS 上执行项目自带的兼容补丁：

```bash
.venv/bin/python scripts/patch_da3_macos.py
```

如果需要系统 FFmpeg：

```bash
brew install ffmpeg
```

### 3. 下载本地模型

```bash
.venv/bin/python scripts/download_models.py
```

默认通过 ModelScope 下载以下模型，下载支持断点续传：

- `facebook/sam3`
- `depth-anything/DA3-LARGE-1.1`

也可以只下载其中一个：

```bash
.venv/bin/python scripts/download_models.py sam3
.venv/bin/python scripts/download_models.py da3
```

模型会放在 `.models/`，该目录不会进入 Git。

### 4. 启动

macOS / Apple Silicon：

```bash
PYTORCH_ENABLE_MPS_FALLBACK=1 \
MPLCONFIGDIR=/tmp/nmm-detect-matplotlib \
.venv/bin/python scripts/run_local.py
```

然后打开：

```text
http://localhost:4173
```

## 使用方法

1. 点击左侧的 **导入模型照片**。上传完成后会自动开始主体分割和深度估算。
2. 如果背包、武器或底座漏选，点击 **补充识别区域**，框住遗漏部位后重新分析。
3. 直接拖动画面中的太阳，确定主光源方向。
4. 使用 **光影强度**、**金属光滑度**和**光影平滑**完成主要调整。
5. 从材质和光谱色调中选择目标效果；特殊需求再展开高级设置。
6. 使用 **原图 / 对比 / 光影**检查结果，最后从右侧面板导出。

### 缩放与局部推理

- 右侧缩放尺支持 `1×` 到 `3×` 视口缩放；
- 停止缩放约 `0.8` 秒后，程序会对当前视口重新进行 `1008 px` 深度推理；
- 这不会凭空创造照片中不存在的细节，但能把原图已有的铆钉、刻线和甲片接缝分配到更多有效采样点；
- 缩回 `1×` 可回到完整模型。

### 快速比较

- **对比模式**：拖动中间分隔线；左侧为原图，右侧为 NMM 光影。
- **临时查看**：按住“原图”按钮，松开后回到原模式。
- **检查镜**：桌面端按住 `Alt/Option` 并移动鼠标。
- **快捷切换**：按 `\` 在原图和上一次光影模式之间切换。

## 导出格式

### 高清对照 PNG

一张适合保存、打印或放在平板旁参考的完整画板，包括：

- 原始照片；
- 照片上的 NMM 上色；
- 纯光影指引；
- 主反射色阶与二、三次反射图例；
- 当前材质、光滑度和光影强度；
- 主光源位置与光线入射方向。

### 微信对比视频 MP4

约 4 秒的无声擦拭动画：

1. 原图停留；
2. 分隔线扫过并显示 NMM；
3. 完整光影停留；
4. 分隔线反向扫回原图。

视频使用 H.264 Main、`yuv420p`、24 fps、1080 像素边界和 fast-start，优先兼容微信、iPhone 相册和常见安卓设备。

### 当前画面 PNG

按当前的原图、对比或光影状态导出。识别矩形不会被画进成品，光源说明会保留。

## 怎样拍出更容易识别的照片

- 推荐使用 **40–60% 明度的中性哑光灰补土**；
- 避免纯黑、纯白、金属色和强反光底漆；
- 使用干净、对比明确但不过曝的背景；
- 从侧上方提供柔和的大面积照明，让细小凹凸保留基础明暗；
- 保证对焦准确，尽量让主体占据画面主要区域；
- 不要使用过强的人像虚化、锐化滤镜或低质量社交软件压缩图。

灰色哑光表面对分割、单目深度和微结构提取更友好，也更接近真实涂装前的工作状态。

## 隐私与离线能力

完成依赖和模型下载后，日常分析不需要联网：

- 照片不会上传到第三方推理服务；
- 模型、推理和导出都在本机进行；
- 分析产物保存在本地 `output/`；
- `output/`、`.models/` 和 `.venv/` 默认被 Git 忽略。

## 项目结构

```text
NMM-detect/
├── index.html                 # 单页界面
├── styles.css                # Spectral Monolith UI
├── app.js                    # 交互、NMM 渲染、比较与导出
├── backend/
│   ├── server.py             # FastAPI、本地分析与 MP4 导出
│   ├── pipeline.py           # SAM 3、DA3 与局部推理工作流
│   └── nmm_shader.py         # 线稿、法线与光影辅助算法
├── scripts/
│   ├── run_local.py          # 本地服务入口
│   ├── download_models.py    # ModelScope 模型下载
│   ├── patch_da3_macos.py    # DA3 macOS 兼容补丁
│   └── smoke_models.py       # 模型冒烟测试
├── docs/
│   └── depth-precision-roadmap.md
└── pyproject.toml
```

## 本地 API

| Endpoint | 用途 |
| --- | --- |
| `GET /api/health` | 检查设备、模型与深度配置 |
| `POST /api/analyze` | 主体分割、深度、法线和线稿分析 |
| `POST /api/refine` | 对选定视口进行局部高精度推理 |
| `POST /api/export-mp4` | 生成 H.264 擦拭对比视频 |
| `POST /api/export-gif` | 旧版 GIF 导出兼容接口 |

## 精度边界

当前默认深度推理分辨率固定为最长边 `1008 px`。这是精度、显存和响应时间之间的实用平衡，并不等同于恢复真实微米级几何。

影响结果的主要因素包括：

- 原始照片是否真正记录了细节；
- 主体在照片中占据的像素数量；
- 表面纹理、涂装和真实凹凸之间是否存在歧义；
- 单张照片无法观察到的遮挡区域；
- 单目深度模型对微型硬表面结构的先验能力。

已讨论的更高分辨率、16-bit 深度、分块融合和局部多尺度方案记录在 [深度与点云精度提升路线图](docs/depth-precision-roadmap.md)。

## 已知限制

- 目前重点验证 Apple Silicon，其他平台需要更多测试与安装说明；
- 首次安装和模型下载体积较大；
- 透明件、镜面表面、极暗或过曝照片会降低分割与深度质量；
- 单张照片无法可靠恢复模型背面或完全遮挡的结构；
- 输出是涂装设计参考，不是物理测量结果，也不是完整 3D 扫描；
- 多光源模式带有艺术化设计，目标是增强可读性，不保证严格满足现实光学。

## 路线图

- [ ] 更完整的 Windows / CUDA 与 Linux 安装流程
- [ ] 可选择的高分辨率分块推理与显存预算
- [ ] 16-bit 深度缓存，减少重复归一化损失
- [ ] 多尺度法线融合，进一步增强铆钉和刻线
- [ ] 参数预设的导入、导出与分享
- [ ] 安卓端模型量化与完全离线可行性验证
- [ ] 更适合打印的涂装分区与编号参考页

## 开发与验证

基础检查：

```bash
node --check app.js
.venv/bin/python -m compileall -q backend scripts
```

模型冒烟测试：

```bash
PYTORCH_ENABLE_MPS_FALLBACK=1 \
MPLCONFIGDIR=/tmp/nmm-detect-matplotlib \
.venv/bin/python scripts/smoke_models.py
```

深度分辨率测试和区域提示测试位于 `scripts/`。这些脚本可能占用较多显存，请避免同时启动多个推理进程。

## 参与项目

欢迎提交 Issue、测试照片的匿名化结果、安装反馈和 Pull Request，尤其欢迎以下方向：

- 不同微缩模型类型的分割失败案例；
- Windows、Linux、CUDA 与较低内存设备测试；
- NMM 色阶、材质和艺术布光方案；
- 局部法线、曲率和边缘提取算法；
- 对微缩涂装初学者更友好的交互与教程。

提交问题时，请尽量附上操作系统、芯片/GPU、内存、Python 版本、照片分辨率和错误日志。公开照片前，请先移除人物、地址或其他私人信息。

如果这个项目对你的涂装有帮助，欢迎点一个 Star、分享你的测试结果，或者告诉我们哪一块金属仍然“不够像金属”。

## 致谢

本项目的本地视觉工作流建立在以下开源生态之上：

- SAM 3：主体识别与分割；
- [Depth Anything 3](https://github.com/ByteDance-Seed/depth-anything-3)：单目几何估算；
- [PyTorch](https://pytorch.org/)：本地模型推理；
- [FastAPI](https://fastapi.tiangolo.com/)：本地服务；
- [FFmpeg](https://ffmpeg.org/)：兼容性视频编码。

模型权重不包含在本仓库中，并分别受其原始发布方许可约束。使用或分发前请查看对应模型页面的许可证与使用条件。

## 许可证

项目代码使用 [Apache License 2.0](LICENSE)。第三方依赖与模型权重仍遵循各自的许可证；Apache 2.0 不会替代或扩大这些第三方许可。

## English overview

NMM-detect turns a single miniature photo into an adjustable Non-Metallic Metal painting guide. The application runs subject segmentation and monocular geometry estimation locally, then uses a deterministic renderer for stepped highlights, secondary reflections, tertiary reflections, artistic fill light and micro-detail lighting.

Key features:

- local-first inference after model download;
- SAM 3 subject segmentation with manual region supplementation;
- Depth Anything 3 depth and surface-normal estimation;
- viewport zoom with local `1008 px` re-inference;
- silver, steel, aluminium, gold, copper and bronze material presets;
- draggable lighting, stepped values and adjustable metal smoothness;
- original/NMM split comparison and inspection lens;
- annotated PNG boards and WeChat-friendly H.264 MP4 exports;
- explicit light-source position and incident-light direction on exports.

The project is currently verified on Apple Silicon with Python 3.12 and is intended as a practical painting reference—not a physically exact scan or an automatic replacement for the painter.
