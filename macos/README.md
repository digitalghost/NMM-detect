# NMM Studio for macOS

原生 AppKit / WKWebView 窗口，内置独立 Python 3.12、当前工作环境的依赖和离线模型。适用于 Apple Silicon / macOS 14+（所附 PyTorch 的最低系统要求）。

## 构建

在已配置 Web 版依赖、DA3 Mac 补丁和模型的项目根目录运行：

```sh
.venv/bin/python macos/build.py --dmg
```

产物位于 `dist/macos/`：`NMM Studio.app` 和 `NMM-Studio-AppleSilicon.dmg`。构建需要 Xcode Command Line Tools，不会下载模型或依赖。应用运行时不依赖项目目录、系统 Python、Homebrew 或用户的虚拟环境。

模型使用现有 `.safetensors` 权重，不复制未使用的 `sam3.pt`。原有包的许可证、元数据和模型说明随应用保留。

## 运行行为

- 本地服务只监听 `127.0.0.1` 的系统分配端口，与 Web 版的 4173 端口互不冲突。
- 服务就绪后显示编辑器；启动失败时显示日志位置。
- 原生照片选择与导出保存对话框，支持 PNG 与 MP4。
- 结果和日志保存在 `~/Library/Application Support/NMM Studio/`；结果缓存限制为最近 20 次、最多 512 MB，并清理超过 7 天的内容。
- 关闭最后一个窗口或按 Command-Q 退出，同时终止本次应用的后端；父进程异常退出也会回收后端。
- 开启 Hugging Face 离线模式，推理使用随包模型；FFmpeg 使用依赖内附的二进制。

当前使用 ad-hoc 签名供本机运行。跨设备公开分发需要 Developer ID 签名及 Apple 公证；此构建不包含这些凭据，也不宣称已公证。

## 渲染回归（0.3.1）

Mac 的 WKWebView 不支持 Canvas 2D `filter`。平滑、细节尺度及照片细节提取使用兼容的像素模糊；二次/三次环境反射使用较宽的反射区域和连续权重，并独立叠加。

```sh
xcrun swiftc macos/RenderRegression.swift -o /tmp/nmm-render-check -framework Cocoa -framework WebKit
/tmp/nmm-render-check "$PWD" "$PWD/macos/render-regression.js"
```

测试直接触发真实页面的滑杆事件，检查最终像素、参数往返一致性、每档细节尺度、三种艺术塑形预设下的反射差异，以及环境反光为零时的关闭行为。第三个可选参数为包含 `cutout/mask/normals/detail_normals/lineart/depth.png` 的真实分析结果目录。第一个参数也可指向应用内的 `Contents/Resources/studio`，用于检查最终打包代码。

使用 `--output dist/macos-0.3.5` 可生成独立的新版本，不覆盖正在运行的旧包。

## 0.3.5 导入与本地服务加固

所有图片格式在进入 WKWebView 前统一转换成最长边 2048 px 的 sRGB PNG。后端只公开三个前端静态文件和分析输出，不再把模型目录、后端源码或应用资源作为静态网站提供；跨站浏览器请求会被拒绝。
# 0.3.4 HEIF 导入更新

HEIF/HEIC 在预览前交给打包应用内的 Pillow/libheif 解码，统一成 RGB PNG 后预览和分析，原文件不变。已通过真实打包应用的 HEIF 导入与完整 SAM/DA3 分析测试；测试和辅助深度评估见 `tests/HEIF_IMPORT_AND_DEPTH.md`。辅助深度目前不参与渲染。
