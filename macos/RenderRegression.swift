// Run against the actual WKWebView engine, without starting the AI backend.
import Cocoa
import WebKit

final class RenderCheck: NSObject, WKNavigationDelegate {
    let web = WKWebView(frame: NSRect(x: 0, y: 0, width: 1280, height: 820))
    let root = URL(fileURLWithPath: CommandLine.arguments[1])
    func start() {
        web.navigationDelegate = self
        web.loadFileURL(root.appendingPathComponent("index.html"), allowingReadAccessTo: root)
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        do {
            let script = try String(contentsOfFile: CommandLine.arguments[2], encoding: .utf8)
            var fixtures: [String: String] = [:]
            if CommandLine.arguments.count > 3 {
                let directory = URL(fileURLWithPath: CommandLine.arguments[3])
                for name in ["cutout", "mask", "normals", "detail_normals", "lineart", "depth"] {
                    fixtures[name] = "data:image/png;base64," + (try Data(contentsOf: directory.appendingPathComponent(name + ".png"))).base64EncodedString()
                }
            }
            web.callAsyncJavaScript("return await (\(script));", arguments: ["fixtures": fixtures], in: nil, in: .page) { result in
                switch result {
                case .success(let value): print(value)
                case .failure(let error): print(error); exit(1)
                }
                NSApp.terminate(nil)
            }
        } catch { print(error); exit(1) }
    }
}
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)
let check = RenderCheck()
check.start()
withExtendedLifetime(check) { app.run() }
