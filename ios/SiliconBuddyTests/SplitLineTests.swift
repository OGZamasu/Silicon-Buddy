import SwiftUI
import XCTest
@testable import SiliconBuddy

/// A label and its reading share a line only when both fit on it whole. At the accessibility
/// sizes an `HStack` with a `Spacer` shared the width out instead, and broke "Memory" beside
/// "38.65 GB of 137.44 GB", and a swarm peer's name beside its URL, inside the word.
@MainActor
final class SplitLineTests: XCTestCase {

    // MARK: - The rule, with the Android side's numbers

    func testTheyShareTheLineOnlyWhenBothFitWhole() {
        // A 411 dp phone's card line at 420 dpi, in pixels, and the 12 dp gap.
        XCTAssertEqual(SplitLine.leadingWidth(in: 869, spacing: 32, leading: 144, trailing: 394), 869 - 32 - 394)
        // Twice the text: the reading goes under "Memory" rather than leaving it 49 px.
        XCTAssertNil(SplitLine.leadingWidth(in: 869, spacing: 32, leading: 288, trailing: 788))
        // Exactly enough is enough.
        XCTAssertEqual(SplitLine.leadingWidth(in: 600, spacing: 20, leading: 300, trailing: 280), 300)
        XCTAssertNil(SplitLine.leadingWidth(in: 600, spacing: 20, leading: 301, trailing: 280))
    }

    func testTheLeadingViewIsNeverGivenLessThanAllOfItself() {
        for width in stride(from: CGFloat(100), through: 1200, by: 37) {
            for leading in stride(from: CGFloat(0), through: 1200, by: 41) {
                for trailing in stride(from: CGFloat(0), through: 1200, by: 43) {
                    guard let given = SplitLine.leadingWidth(
                        in: width, spacing: 12, leading: leading, trailing: trailing
                    ) else { continue }
                    XCTAssertGreaterThanOrEqual(given, leading)
                    XCTAssertLessThanOrEqual(given + 12 + trailing, width)
                }
            }
        }
    }

    // MARK: - The layout, drawn

    /// A red 100 × 20 and a blue 150 × 30, in a line `width` wide, on white.
    private func probe(width: CGFloat) throws -> (size: CGSize, color: (Int, Int) -> String) {
        let view = SplitLine {
            Color(red: 1, green: 0, blue: 0).frame(width: 100, height: 20)
            Color(red: 0, green: 0, blue: 1).frame(width: 150, height: 30)
        }
        .frame(width: width)
        .fixedSize(horizontal: false, vertical: true)
        .background(Color.white)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.cgImage)
        let columns = image.width, rows = image.height
        var pixels = [UInt8](repeating: 0, count: columns * rows * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: columns, height: rows, bitsPerComponent: 8,
                bytesPerRow: columns * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: columns, height: rows))
            return true
        }
        XCTAssertTrue(drawn)
        let color = { (x: Int, y: Int) -> String in
            let at = (y * columns + x) * 4
            let (r, g, b) = (pixels[at], pixels[at + 1], pixels[at + 2])
            if r > 200, g < 80, b < 80 { return "red" }
            if r < 80, g < 80, b > 200 { return "blue" }
            if r > 200, g > 200, b > 200 { return "white" }
            return "(\(r),\(g),\(b))"
        }
        return (CGSize(width: image.width, height: image.height), color)
    }

    func testWithRoomTheyShareTheLineAtItsTwoEnds() throws {
        let line = try probe(width: 300)
        XCTAssertEqual(line.size.height, 30, "one line, as tall as the taller")
        XCTAssertEqual(line.color(50, 15), "red", "the first at the leading edge")
        XCTAssertEqual(line.color(225, 15), "blue", "the second at the trailing edge")
        XCTAssertEqual(line.color(125, 15), "white", "and the room left between them")
    }

    func testWithoutRoomTheSecondGoesUnderTheFirstWhole() throws {
        let line = try probe(width: 200)
        XCTAssertEqual(line.size.height, 52, "20, 2 between, then 30")
        XCTAssertEqual(line.color(50, 10), "red")
        XCTAssertEqual(line.color(150, 10), "white", "the second is not beside the first")
        XCTAssertEqual(line.color(75, 40), "blue", "it is under it, from the leading edge")
        XCTAssertEqual(line.color(175, 40), "white")
    }

    // MARK: - The dashboard's rows, drawn

    private func height(_ row: some View, size: DynamicTypeSize, name: String) throws -> CGFloat {
        let view = row
            .padding(20)
            .frame(width: 353)
            .fixedSize(horizontal: false, vertical: true)
            .background(Color.white)
            .environment(\.dynamicTypeSize, size)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.uiImage, "\(name) did not draw")
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        return image.size.height
    }

    func testTheMemoryMeterStacksAtTheLargestSizeAndNotAtTheUsualOne() throws {
        let memory = MeterRow(label: "Memory", detail: "38.65 GB of 137.44 GB", fraction: 0.28)
        let gpu = MeterRow(label: "GPU", detail: "12%", fraction: 0.12)
        XCTAssertEqual(
            try height(memory, size: .large, name: "meter-memory-default"),
            try height(gpu, size: .large, name: "meter-gpu-default"),
            accuracy: 1, "at the usual size both are one line and a bar"
        )
        XCTAssertGreaterThan(
            try height(memory, size: .accessibility5, name: "meter-memory-ax5"),
            try height(gpu, size: .accessibility5, name: "meter-gpu-ax5") * 1.3,
            "at AX5 the reading goes under the label"
        )
    }
}
