import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import Vision

/// How a sticker is made from its picture. Saved with the sticker, so it can be edited later.
struct StickerOptions: Codable, Equatable {
    /// Cut the subject out of its background (the iPhone's own subject finder).
    var smartCut = false
    /// Which of the things found to keep; empty keeps them all.
    var keep: [Int] = []
    var outline = true
    /// The white outline's thickness, in pixels of the 512-pixel sticker.
    var outlineWidth: Double = 12
    var whiteBackground = false
    /// 0...100.
    var sharpen: Double = 0
    /// A short title in white letters beneath the picture.
    var caption = ""
}

/// The people and things the iPhone finds in a picture (Vision's subject lifting), each one separately.
final class SubjectFinder: @unchecked Sendable {
    private let observation: VNInstanceMaskObservation
    private let handler: VNImageRequestHandler

    private init(observation: VNInstanceMaskObservation, handler: VNImageRequestHandler) {
        self.observation = observation
        self.handler = handler
    }

    /// Nil when nothing stands out in the picture, or the finder cannot run (it does not in the simulator).
    static func find(in image: CGImage) -> SubjectFinder? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        guard let observation = request.results?.first, !observation.allInstances.isEmpty else { return nil }
        return SubjectFinder(observation: observation, handler: handler)
    }

    var instances: [Int] { Array(observation.allInstances) }

    /// The mask of the chosen things (all of them when [keep] is empty), at the picture's size.
    func mask(keep: [Int]) -> CIImage? {
        let chosen = IndexSet(keep).intersection(observation.allInstances)
        let wanted = chosen.isEmpty ? observation.allInstances : chosen
        guard let buffer = try? observation.generateScaledMaskForImage(forInstances: wanted, from: handler) else { return nil }
        return CIImage(cvPixelBuffer: buffer)
    }

    /// Which thing is at a point of the picture (0...1 across and down); nil on the background.
    func instance(atX x: CGFloat, y: CGFloat) -> Int? {
        let buffer = observation.instanceMask
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let column = min(max(Int(x * CGFloat(width)), 0), width - 1)
        let row = min(max(Int(y * CGFloat(height)), 0), height - 1)
        let value = base.advanced(by: row * CVPixelBufferGetBytesPerRow(buffer) + column).load(as: UInt8.self)
        return value == 0 ? nil : Int(value)
    }
}

/// Turns a picture into a sticker: cut out, sharpened, outlined in white, titled, in a 512-pixel square.
enum StickerEngine {
    static let size: CGFloat = 512
    private static let margin: CGFloat = 4
    private static let captionBand: CGFloat = 64
    private static let context = CIContext()

    /// The picture no larger than [maxSide] on its long side, upright, one pixel per point.
    static func fitted(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let w = image.size.width * image.scale
        let h = image.size.height * image.scale
        let s = min(1, maxSide / max(w, h, 1))
        let target = CGSize(width: max(1, (w * s).rounded()), height: max(1, (h * s).rounded()))
        return UIGraphicsImageRenderer(size: target, format: format()).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    private static func format() -> UIGraphicsImageRendererFormat {
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        f.opaque = false
        return f
    }

    /// The picture with the subject cut out (if asked and found) and sharpened, before it is fitted and outlined.
    static func cutOut(_ original: UIImage, options: StickerOptions, subjects: SubjectFinder?) -> UIImage {
        guard let cg = original.cgImage else { return original }
        var image = CIImage(cgImage: cg)
        let extent = image.extent
        if options.sharpen > 0 {
            let sharpen = CIFilter.unsharpMask()
            sharpen.inputImage = image
            sharpen.radius = 2.5
            sharpen.intensity = Float(options.sharpen / 100 * 1.5)
            image = sharpen.outputImage?.cropped(to: extent) ?? image
        }
        if options.smartCut, let mask = subjects?.mask(keep: options.keep) {
            let blend = CIFilter.blendWithMask()
            blend.inputImage = image
            blend.maskImage = mask
            blend.backgroundImage = CIImage.empty()
            image = blend.outputImage?.cropped(to: extent) ?? image
        }
        guard let out = context.createCGImage(image, from: extent) else { return original }
        return UIImage(cgImage: out)
    }

    /// The picture with what the cut drops dimmed, for choosing who or what to keep by tapping.
    static func choosingPreview(_ original: UIImage, subjects: SubjectFinder, keep: [Int]) -> UIImage {
        guard let cg = original.cgImage, let mask = subjects.mask(keep: keep) else { return original }
        let image = CIImage(cgImage: cg)
        let dim = CIFilter.colorControls()
        dim.inputImage = image
        dim.brightness = -0.45
        dim.saturation = 0.2
        let blend = CIFilter.blendWithMask()
        blend.inputImage = image
        blend.maskImage = mask
        blend.backgroundImage = dim.outputImage ?? image
        guard let output = blend.outputImage, let out = context.createCGImage(output, from: image.extent) else { return original }
        return UIImage(cgImage: out)
    }

    /// The sticker.
    static func make(_ original: UIImage, options: StickerOptions, subjects: SubjectFinder?) -> UIImage {
        let cut = trimmed(cutOut(original, options: options, subjects: subjects))
        let border = options.outline ? CGFloat(min(max(options.outlineWidth, 2), 40)) : 0
        let caption = String(options.caption.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces).prefix(40))
        let band = caption.isEmpty ? 0 : captionBand
        let inner = size - 2 * (border + margin)
        let scale = min(inner / cut.size.width, (inner - band) / cut.size.height)
        let w = max(1, (cut.size.width * scale).rounded())
        let h = max(1, (cut.size.height * scale).rounded())
        let rect = CGRect(x: (size - w) / 2, y: (size - band - h) / 2, width: w, height: h)

        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format()).image { renderer in
            if options.whiteBackground {
                UIColor.white.setFill()
                renderer.fill(CGRect(x: 0, y: 0, width: size, height: size))
            }
            if border > 0 {
                // The picture's own shape in white, stamped around circles out to the thickness: an even outline.
                let white = silhouette(cut, size: rect.size)
                var radius = border
                while radius > 0 {
                    let steps = max(32, Int(radius * 4))
                    for step in 0..<steps {
                        let angle = 2 * CGFloat.pi * CGFloat(step) / CGFloat(steps)
                        white.draw(in: rect.offsetBy(dx: cos(angle) * radius, dy: sin(angle) * radius))
                    }
                    radius -= 6
                }
            }
            cut.draw(in: rect)
            if !caption.isEmpty { draw(caption: caption, below: rect.maxY + border) }
        }
    }

    /// [image]'s shape, all white.
    private static func silhouette(_ image: UIImage, size: CGSize) -> UIImage {
        UIGraphicsImageRenderer(size: size, format: format()).image { renderer in
            let rect = CGRect(origin: .zero, size: size)
            image.draw(in: rect)
            UIColor.white.setFill()
            renderer.fill(rect, blendMode: .sourceIn)
        }
    }

    /// White letters centred beneath the picture, with a thin dark edge so they read on any background.
    private static func draw(caption: String, below: CGFloat) {
        let room = size - 4 * margin
        var fontSize: CGFloat = 44
        var font = UIFont.boldSystemFont(ofSize: fontSize)
        while fontSize > 22, (caption as NSString).size(withAttributes: [.font: font]).width > room {
            fontSize -= 2
            font = UIFont.boldSystemFont(ofSize: fontSize)
        }
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byTruncatingTail
        let lineHeight = font.lineHeight
        let rect = CGRect(x: 2 * margin, y: below + (captionBand - lineHeight) / 2, width: room, height: lineHeight)
        // The edge first (a stroke only), then the white letters over it.
        (caption as NSString).draw(in: rect, withAttributes: [
            .font: font, .paragraphStyle: paragraph, .strokeColor: UIColor.black.withAlphaComponent(0.85), .strokeWidth: 9,
            .foregroundColor: UIColor.clear,
        ])
        (caption as NSString).draw(in: rect, withAttributes: [.font: font, .paragraphStyle: paragraph, .foregroundColor: UIColor.white])
    }

    /// Cuts away the fully transparent rows and columns around the picture.
    static func trimmed(_ image: UIImage) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let w = cg.width
        let h = cg.height
        guard w > 0, h > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return image }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return image }
        let pixels = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        var left = w, right = -1, top = h, bottom = -1
        for y in 0..<h {
            for x in 0..<w where pixels[(y * w + x) * 4 + 3] > 8 {
                if x < left { left = x }
                if x > right { right = x }
                if y < top { top = y }
                if y > bottom { bottom = y }
            }
        }
        if right < left || bottom < top { return image } // nothing left: as it is
        if left == 0, top == 0, right == w - 1, bottom == h - 1 { return image }
        // The bitmap's rows run top to bottom, as the image's do.
        guard let cropped = cg.cropping(to: CGRect(x: left, y: top, width: right - left + 1, height: bottom - top + 1)) else { return image }
        return UIImage(cgImage: cropped)
    }
}
