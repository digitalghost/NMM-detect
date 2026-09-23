# NMM Android CPU MVP

这是 NMM-detect 的 Android 本地推理最小版。它不再是单模型探针，而是已经串通的基础工作流：

1. 从系统相册选择照片；
2. SAM 3 以固定 `miniature figure` 文本特征识别主体；
3. DA3-LARGE-1.1 估算深度；
4. 固定算法恢复表面法线并生成主高光、二次反射和三次反射；
5. 通过擦拭滑条比较原图与 NMM，保存带光源方向说明的 PNG。

两套模型在短生命周期的独立进程中严格串行运行，以免两个大模型同时驻留。SAM 3 使用 ORT CPU 保证蒙版数值与桌面端一致；DA3 使用 XNNPACK CPU。每个阶段完成后销毁对应进程，确定性释放约 6–7 GB 原生临时内存。

## 模型文件

模型权重不打包进 APK。应用需要以下文件位于私有 `files/` 目录：

- `da3-large-1008x756.onnx`
- `da3-large-1008x756.onnx.data`
- `sam3-miniature-1008.onnx`
- `sam3-miniature-1008.onnx.data`

调试设备可用下面的方式写入（安装 APK 后执行）：

```bash
adb push android-probe/models/da3-large-1008x756.onnx /data/local/tmp/
adb push android-probe/models/da3-large-1008x756.onnx.data /data/local/tmp/
adb push android-probe/models/sam3-miniature-1008.onnx /data/local/tmp/
adb push android-probe/models/sam3-miniature-1008.onnx.data /data/local/tmp/

adb shell run-as com.digitalghost.nmmprobe cp /data/local/tmp/da3-large-1008x756.onnx files/
adb shell run-as com.digitalghost.nmmprobe cp /data/local/tmp/da3-large-1008x756.onnx.data files/
adb shell run-as com.digitalghost.nmmprobe cp /data/local/tmp/sam3-miniature-1008.onnx files/
adb shell run-as com.digitalghost.nmmprobe cp /data/local/tmp/sam3-miniature-1008.onnx.data files/
```

正式产品需要补充首次启动时的模型包导入或随安装包分发机制。

## 构建

工程自带 Gradle 8.7 Wrapper。先准备 JDK 17、Android SDK，并将验证过的
`onnxruntime-android-1.30.0.aar` 放到 `app/libs/`，然后执行：

```bash
cd android-probe
./gradlew :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。当前真机验证环境为
Android 16、Qualcomm SM8750P、Adreno 830、12 GB 内存。

## 已验证结果

- Web 原始测试照片：`1280×1706`；
- SAM 3 / ORT CPU：正确选择完整人物 query 157，移动端分数 `0.780`；
- DA3-LARGE-1.1 / XNNPACK CPU：完整完成深度推理；
- 端到端总耗时：`76 秒`；
- 每个模型结束后独立进程退出，系统可用内存恢复到约 `7.7 GB`；
- PNG 成功写入 `Pictures/NMM-detect`，包含擦拭对比和主光入射箭头。

SAM 3 没有使用 XNNPACK：实测同一个 ONNX 图在该执行器上发生明显数值偏差，
会把完整人物 query 157 错排为底座 query 11。标准 ORT CPU 与桌面端结果一致。

## 当前边界

- 当前只提供自动主体识别，尚未移植桌面版的补充框选。
- 模型输入为固定尺寸；第一版会将照片缩放到对应模型尺寸。
- NMM 渲染器是 Android 原生固定算法的基础实现，尚未移植桌面版全部材质和高级参数。
- Vulkan 实验已冻结；本版本不包含 ExecuTorch Vulkan 依赖。
