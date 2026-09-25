import Cocoa

let folder = URL(fileURLWithPath: CommandLine.arguments[1])
try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let image = NSImage(size: NSSize(width: pixels, height: pixels))
        image.lockFocus()
        let transform = AffineTransform(scale: CGFloat(pixels) / 1024)
        (transform as NSAffineTransform).concat()
        let rect = NSRect(x: 48, y: 48, width: 928, height: 928)
        NSColor(calibratedRed: 0.04, green: 0.09, blue: 0.15, alpha: 1).setFill()
        NSBezierPath(roundedRect: rect, xRadius: 205, yRadius: 205).fill()
        let text = "N" as NSString
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 650, weight: .black),
            .foregroundColor: NSColor(calibratedRed: 0.45, green: 0.83, blue: 1, alpha: 1)
        ]
        let measure = text.size(withAttributes: attrs)
        text.draw(at: NSPoint(x: (1024 - measure.width) / 2, y: 150), withAttributes: attrs)
        for index in 0..<5 {
            NSColor(calibratedWhite: 0.22 + CGFloat(index) * 0.18, alpha: 1).setFill()
            NSBezierPath(roundedRect: NSRect(x: 252 + index * 106, y: 165, width: 94, height: 36), xRadius: 8, yRadius: 8).fill()
        }
        image.unlockFocus()
        let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
        let name = "icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"
        try bitmap.representation(using: .png, properties: [:])!.write(to: folder.appendingPathComponent(name))
    }
}
