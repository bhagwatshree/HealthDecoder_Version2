import UIKit

/// Mirrors `util/ImageUtil.kt`: downsamples + re-encodes so multi-page scans stay under
/// memory/API-payload limits. Drawing through `UIGraphicsImageRenderer` bakes in the image's
/// EXIF orientation automatically (the renderer respects `UIImage.imageOrientation`), the same
/// effect Android gets from manually applying the EXIF rotation matrix.
enum ImageUtil {
    static let maxDimension: CGFloat = 1600
    static let jpegQuality: CGFloat = 0.85

    static func compressedJPEG(from image: UIImage) -> Data? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return image.jpegData(compressionQuality: jpegQuality) }

        let longest = max(size.width, size.height)
        let scale = longest > maxDimension ? maxDimension / longest : 1.0
        let targetSize = CGSize(width: size.width * scale, height: size.height * scale)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let normalized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return normalized.jpegData(compressionQuality: jpegQuality)
    }
}
