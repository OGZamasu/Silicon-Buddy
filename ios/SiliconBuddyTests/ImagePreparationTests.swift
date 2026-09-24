import ImageIO
import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import SiliconBuddy

/// Pictures on their way to a vision model, made small enough to send — and made small
/// without ever holding one at full size, which a share extension is not allowed to.
final class ImagePreparationTests: XCTestCase {

    /// A 48-megapixel photo is 195 MB decoded. The share sheet's limit is well under that,
    /// so preparing one must never hold the whole bitmap. HEIC, because that is what the
    /// camera takes — and because decoding one and redrawing it smaller, which the share
    /// sheet used to do, held about twice that.
    func testAFortyEightMegapixelPhotoIsPreparedWithoutDecodingItAtFullSize() throws {
        let photo = try XCTUnwrap(Self.picture(.heic, width: 8064, height: 6048))
        let before = Self.footprint()
        let sampler = PeakSampler()
        sampler.start()
        let attachment = ImagePreparation.attachment(fromData: photo)
        let peak = sampler.stop()

        let prepared = try XCTUnwrap(attachment)
        let image = try XCTUnwrap(UIImage(data: prepared.jpeg))
        XCTAssertEqual(max(image.size.width, image.size.height), 1024, accuracy: 1)
        XCTAssertLessThanOrEqual(prepared.jpeg.count, SendLimits.maximumImageBytes)
        let grew = Int64(peak) - Int64(before)
        XCTAssertLessThan(
            grew, 60_000_000,
            "Preparing it took \(grew / 1_000_000) MB; the full bitmap is 195 MB"
        )
    }

    /// A photo taken with the phone on its side is stored sideways with a note saying
    /// so. What the Mac is sent is the picture the way the person saw it.
    func testThePhotosOrientationIsKept() throws {
        let sideways = try XCTUnwrap(Self.picture(.jpeg, width: 400, height: 200, orientation: .right))
        let prepared = try XCTUnwrap(ImagePreparation.attachment(fromData: sideways))
        let image = try XCTUnwrap(UIImage(data: prepared.jpeg))
        XCTAssertEqual(image.size, CGSize(width: 200, height: 400))
    }

    /// The share sheet is handed a file as often as bytes; that is read without being
    /// loaded whole either.
    func testAPictureSharedAsAFileIsPreparedFromTheFile() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".heic")
        try XCTUnwrap(Self.picture(.heic, width: 3000, height: 2000)).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let prepared = try XCTUnwrap(ImagePreparation.attachment(contentsOf: file))
        let image = try XCTUnwrap(UIImage(data: prepared.jpeg))
        XCTAssertEqual(image.size.width, 1024, accuracy: 1)
    }

    func testASmallPictureIsNotEnlarged() throws {
        let small = try XCTUnwrap(Self.picture(.jpeg, width: 300, height: 200))
        let prepared = try XCTUnwrap(ImagePreparation.attachment(fromData: small))
        let image = try XCTUnwrap(UIImage(data: prepared.jpeg))
        XCTAssertEqual(image.size, CGSize(width: 300, height: 200))
    }

    func testBytesThatAreNotAPictureGiveNothing() {
        XCTAssertNil(ImagePreparation.attachment(fromData: Data("not a picture".utf8)))
    }

    // MARK: - Helpers

    /// A picture of the given size and type: a gradient, drawn once and encoded.
    private static func picture(
        _ type: UTType, width: Int, height: Int, orientation: CGImagePropertyOrientation = .up
    ) -> Data? {
        autoreleasepool {
            guard let context = CGContext(
                data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return nil }
            let colors = [UIColor.systemTeal.cgColor, UIColor.systemOrange.cgColor] as CFArray
            guard let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]
            ) else { return nil }
            context.drawLinearGradient(
                gradient, start: .zero, end: CGPoint(x: width, y: height), options: []
            )
            guard let image = context.makeImage() else { return nil }
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(
                data, type.identifier as CFString, 1, nil
            ) else { return nil }
            CGImageDestinationAddImage(destination, image, [
                kCGImageDestinationLossyCompressionQuality: 0.9,
                kCGImagePropertyOrientation: orientation.rawValue,
            ] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { return nil }
            return data as Data
        }
    }

    /// This process's physical footprint, the number the system kills an extension by.
    fileprivate static func footprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}

/// The highest footprint seen while it runs, read every millisecond on its own thread.
private final class PeakSampler: @unchecked Sendable {
    private let lock = NSLock()
    private var peak: UInt64 = 0
    private var running = true
    private let done = DispatchSemaphore(value: 0)

    func start() {
        peak = ImagePreparationTests.footprint()
        let thread = Thread { [self] in
            while lock.withLock({ running }) {
                let now = ImagePreparationTests.footprint()
                lock.withLock { peak = max(peak, now) }
                usleep(1_000)
            }
            done.signal()
        }
        // As urgent as the test that waits for it, so the wait is not an inversion.
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    func stop() -> UInt64 {
        lock.withLock { running = false }
        done.wait()
        let now = ImagePreparationTests.footprint()
        return lock.withLock { max(peak, now) }
    }
}
