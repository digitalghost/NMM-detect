import Cocoa
import WebKit
import UniformTypeIdentifiers

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, WKUIDelegate, WKNavigationDelegate, WKScriptMessageHandlerWithReply {
    var window: NSWindow!
    var web: WKWebView!
    var server: Process?
    var timer: Timer?
    var readyFile: URL!
    var serverURL: URL?
    var attempts = 0
    var checking = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        start()
    }

    func start() {
        guard window == nil else { return }
        let menu = NSMenu()
        let appItem = NSMenuItem(); menu.addItem(appItem)
        let appMenu = NSMenu(); appItem.submenu = appMenu
        appMenu.addItem(withTitle: "关于 NMM Studio", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "退出 NMM Studio", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let edit = NSMenuItem(); menu.addItem(edit); edit.submenu = NSMenu(title: "编辑")
        for (name, action, key) in [("拷贝", "copy:", "c"), ("粘贴", "paste:", "v"), ("全选", "selectAll:", "a")] {
            edit.submenu?.addItem(withTitle: name, action: Selector(action), keyEquivalent: key)
        }
        NSApp.mainMenu = menu
        let config = WKWebViewConfiguration()
        config.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "saveFile")
        let bridge = """
        window.NMMMac = {saveFile: (data, name) => window.webkit.messageHandlers.saveFile.postMessage({data, name})};
        """
        config.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: .zero, configuration: config)
        web.uiDelegate = self; web.navigationDelegate = self
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 820), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "NMM Studio · 本地光影预演"
        window.minSize = NSSize(width: 980, height: 680)
        window.contentView = web; window.delegate = self
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        showMessage("正在启动 NMM Studio", "首次启动需要一些时间。模型在本机运行，照片无需上传。")
        startServer()
    }

    func showMessage(_ title: String, _ message: String) {
        web.loadHTMLString("<html><body style='background:#08111c;color:#dceaf4;font:18px -apple-system;padding:12%;line-height:1.8'><h1>\(title)</h1><p>\(message)</p></body></html>", baseURL: nil)
    }

    func startServer() {
        do {
            let resources = Bundle.main.resourceURL!
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("NMM Studio")
            try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            readyFile = support.appendingPathComponent("port-\(ProcessInfo.processInfo.processIdentifier).txt")
            try? FileManager.default.removeItem(at: readyFile)
            let log = support.appendingPathComponent("backend.log")
            FileManager.default.createFile(atPath: log.path, contents: nil)
            let handle = try FileHandle(forWritingTo: log)
            let task = Process()
            task.executableURL = resources.appendingPathComponent("python/bin/python3.12")
            task.arguments = ["-I", "-B", resources.appendingPathComponent("server_launcher.py").path, readyFile.path]
            task.currentDirectoryURL = support
            var env = ProcessInfo.processInfo.environment
            env["NMM_OUTPUT_ROOT"] = support.appendingPathComponent("output").path
            env["NMM_OUTPUT_MAX_RUNS"] = "20"
            env["NMM_OUTPUT_MAX_BYTES"] = "536870912"
            env["NMM_OUTPUT_MAX_AGE_SECONDS"] = "604800"
            env["PYTHONUNBUFFERED"] = "1"
            env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
            task.environment = env; task.standardOutput = handle; task.standardError = handle
            task.terminationHandler = { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self = self, self.timer != nil || self.serverURL != nil else { return }
                    self.timer?.invalidate(); self.timer = nil
                    self.showMessage("本地服务已停止", "请重新打开应用。诊断日志位于 ~/Library/Application Support/NMM Studio/backend.log")
                }
            }
            server = task; try task.run()
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.checkServer() }
        } catch {
            showMessage("无法启动本地服务", "请将应用复制到“应用程序”后重试。")
        }
    }

    func checkServer() {
        attempts += 1
        if attempts > 240 {
            timer?.invalidate(); timer = nil; server?.terminate()
            showMessage("启动超时", "请重新打开应用；诊断日志位于 ~/Library/Application Support/NMM Studio/backend.log")
            return
        }
        guard !checking, let value = try? String(contentsOf: readyFile, encoding: .utf8), let port = Int(value),
              let url = URL(string: "http://127.0.0.1:\(port)") else { return }
        checking = true
        URLSession.shared.dataTask(with: url.appendingPathComponent("api/health")) { [weak self] _, response, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }; self.checking = false
                guard self.timer != nil, (response as? HTTPURLResponse)?.statusCode == 200 else { return }
                self.timer?.invalidate(); self.timer = nil; self.serverURL = url
                self.web.load(URLRequest(url: url))
            }
        }.resume()
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false; panel.canChooseDirectories = false
        panel.beginSheetModal(for: window) { result in completionHandler(result == .OK ? panel.urls : nil) }
    }

    // Optional packaged-build smoke check. Runs in the real WKWebView engine,
    // so a JavaScript startup failure is detected separately from HTTP health.
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if serverURL != nil, let flag = CommandLine.arguments.firstIndex(of: "--check-import"),
           CommandLine.arguments.count > flag + 2 {
            let fixture = URL(fileURLWithPath: CommandLine.arguments[flag + 1])
            let report = URL(fileURLWithPath: CommandLine.arguments[flag + 2])
            do {
                let bytes = try Data(contentsOf: fixture).base64EncodedString()
                webView.callAsyncJavaScript("""
                const bytes=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));
                await importPhoto(new File([bytes],name,{type:'application/octet-stream'}));
                return JSON.stringify({passed:aiNormalReady&&aiLineartReady&&currentUploadFile?.type==='image/png',
                    width:canvas.width,height:canvas.height,uploadType:currentUploadFile?.type,
                    analysisId:currentAnalysisId,status:document.querySelector('#aiStatus').textContent});
                """, arguments: ["encoded": bytes,"name":fixture.lastPathComponent], in: nil, in: .page) { result in
                    let text: String
                    switch result { case .success(let value): text = value as? String ?? "{\"error\":\"Empty result\"}"
                    case .failure(let error): text = "{\"error\":\"Import check failed\"}"; NSLog("%@",error.localizedDescription) }
                    try? text.write(to: report, atomically: true, encoding: .utf8)
                }
            } catch { try? "{\"error\":\"Fixture unreadable\"}".write(to: report, atomically: true, encoding: .utf8) }
            return
        }
        guard serverURL != nil,
              let flag = CommandLine.arguments.firstIndex(of: "--check-ui"),
              CommandLine.arguments.count > flag + 1 else { return }
        let report = URL(fileURLWithPath: CommandLine.arguments[flag + 1])
        webView.evaluateJavaScript("""
        JSON.stringify({
          bridge: typeof window.NMMMac?.saveFile === 'function',
          canvas: document.querySelector('#mainCanvas')?.width > 0,
          initialized: typeof state !== 'undefined' && state.hasImage,
          fileInput: !!document.querySelector('#imageInput'),
          exportButton: !!document.querySelector('#downloadButton'),
          origin: location.origin
        })
        """) { value, error in
            let result = value as? String ?? "{\"error\":\"JavaScript startup failed\"}"
            try? result.write(to: report, atomically: true, encoding: .utf8)
            if error != nil { NSLog("NMM UI check failed: %@", error!.localizedDescription) }
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage, replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, let origin = serverURL,
              message.frameInfo.securityOrigin.host == origin.host,
              message.frameInfo.securityOrigin.port == origin.port,
              let body = message.body as? [String: String], let text = body["data"],
              let comma = text.firstIndex(of: ","), let bytes = Data(base64Encoded: String(text[text.index(after: comma)...])) else {
            replyHandler(nil, "无法读取导出文件"); return
        }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = URL(fileURLWithPath: body["name"] ?? "NMM.png").lastPathComponent
        panel.beginSheetModal(for: window) { result in
            guard result == .OK, let target = panel.url else { replyHandler(nil, "已取消保存"); return }
            do { try bytes.write(to: target, options: .atomic); replyHandler(true, nil) }
            catch { replyHandler(nil, "保存失败：\(error.localizedDescription)") }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if url.scheme == "about" || (url.host == serverURL?.host && url.port == serverURL?.port) { decisionHandler(.allow) }
        else { decisionHandler(.cancel) }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) {
        timer?.invalidate(); timer = nil
        serverURL = nil
        if server?.isRunning == true { server?.terminate() }
        if let path = readyFile { try? FileManager.default.removeItem(at: path) }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
withExtendedLifetime(delegate) {
    app.finishLaunching()
    delegate.start()
    app.run()
}
