import Foundation
import UIKit

/// What a device may send, from the Mac's own contract: 4 MiB a body, about 1.5 MB an
/// image, eight images a message. Checked before sending rather than discovered as a 413
/// after a slow upload over a tailnet.
public enum SendLimits {
    public static let maximumAttachments = 8
    public static let maximumImageBytes = 1_500_000
    public static let maximumBodyBytes = 4 * 1024 * 1024
}

/// A picture on its way to a vision model.
public struct ChatAttachment: Identifiable, Sendable, Equatable {
    public let id = UUID()
    /// JPEG bytes, already scaled down.
    public let jpeg: Data

    public init(jpeg: Data) { self.jpeg = jpeg }

    /// The form the control API wants: a base64 `data:` URL.
    public var dataURL: String {
        "data:image/jpeg;base64," + jpeg.base64EncodedString()
    }
}

public enum ImagePreparation {
    /// Scales a picture down and re-encodes it as JPEG.
    ///
    /// A 12-megapixel photo as a base64 data URL is about 15 MB of JSON, which is both
    /// slower to send than the model is to answer and larger than most vision encoders
    /// can use. 1024 on the long edge is what they actually look at.
    public static func attachment(
        from image: UIImage, maxEdge: CGFloat = 1024, quality: CGFloat = 0.8
    ) -> ChatAttachment? {
        var edge = maxEdge
        var compression = quality
        // Four tries at most: a 12-megapixel photo of a page of text can still beat the
        // Mac's per-image limit at 1024px, and a picture that is refused on arrival is
        // worse than one that was made smaller before it left.
        for _ in 0..<4 {
            let scaled = resize(image, maxEdge: edge)
            guard let data = scaled.jpegData(compressionQuality: compression) else { return nil }
            if data.count <= SendLimits.maximumImageBytes {
                return ChatAttachment(jpeg: data)
            }
            edge *= 0.75
            compression = max(0.4, compression - 0.15)
        }
        // The last attempt is only worth returning if it is finally inside the cap.
        // Handing back an oversized one made `attach` refuse it a moment later with a
        // different message, and left `ShareNormaliser` to drop it silently — a picture
        // that vanished between being chosen and being sent.
        guard let last = resize(image, maxEdge: edge).jpegData(compressionQuality: 0.4),
              last.count <= SendLimits.maximumImageBytes
        else { return nil }
        return ChatAttachment(jpeg: last)
    }

    static func resize(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxEdge, longest > 0 else { return image }
        let scale = maxEdge / longest
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
