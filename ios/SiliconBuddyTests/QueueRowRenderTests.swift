import SwiftUI
import XCTest
@testable import SiliconBuddy

/// The queue's rows, drawn: every state a clip can be in, at the default text size and at
/// the largest accessibility size, full control and chat-only.
///
/// What is checked is that each draws at all and that the large sizes grow the rows rather
/// than squeezing them — a verb cut to "Cancel re…" is a button nobody can read. The
/// drawings are attached to the test result, so a person can look at them too.
/// `ImageRenderer` draws a UIKit-backed view as a placeholder, so a running clip's
/// spinner shows there as a crossed-out square; on a phone it is a spinner.
@MainActor
final class QueueRowRenderTests: XCTestCase {

    private func item(
        _ id: String, _ title: String, status: String, canCancel: Bool?,
        cancelState: String? = nil, cancelDetail: String? = nil, error: String? = nil
    ) -> ControlAPI.VideoQueueView.Item {
        ControlAPI.VideoQueueView.Item(
            id: id, batchID: "9C2F", title: title, prompt: "Laundry lines over the Alfama steps",
            scene: 5, variation: 1, seed: 424246, modelID: "ltx2-distilled", seconds: 5,
            resolution: "720p", h3Turbo: nil, status: status, nodeJobID: "job-1191", file: nil,
            outputDirectory: "/Users/you/Movies/Silicon/Lisbon", error: error,
            uncertainSubmission: false, cancelState: cancelState, cancelDetail: cancelDetail,
            canCancel: canCancel
        )
    }

    /// One clip in each state the row has something different to say about.
    private func model() async -> QueueModel {
        let mac = StubTransport()
        mac.videoQueueResult = .success(ControlAPI.VideoQueueView(
            paused: false, activeID: "A",
            message: "The node stopped this render. Nothing will be published for it.",
            items: [
                item("A", "Alfama", status: "rendering", canCancel: true),
                item("B", "Harbour", status: "failed", canCancel: true,
                     error: "Stopped following. The node may still be rendering."),
                item("C", "Tram bell", status: "cancelled", canCancel: false, cancelState: "confirmed",
                     cancelDetail: "Cancelled; the renderer was stopped."),
                item("D", "Rooftops", status: "rendering", canCancel: false, cancelState: "unknown",
                     cancelDetail: "Could not reach the node, so the render may still be running."),
                item("E", "Dusk", status: "pending", canCancel: false),
            ]
        ))
        let model = QueueModel()
        await model.refresh(using: mac)
        return model
    }

    private func appModel() -> AppModel {
        let suite = "buddy.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.\(UUID().uuidString)")
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suite)
            tokens.delete()
        }
        return AppModel(defaults: defaults, tokens: tokens)
    }

    /// The rows drawn one under another at a phone's width, and the picture attached.
    private func draw(
        _ model: QueueModel, size: DynamicTypeSize, canControl: Bool, name: String
    ) throws -> CGSize {
        let rows = VStack(alignment: .leading, spacing: 12) {
            ForEach(model.items) { item in
                QueueRow(item: item, model: model, canControl: canControl, confirm: { _ in })
                Divider()
            }
        }
        .padding()
        .frame(width: 390)
        .fixedSize(horizontal: false, vertical: true)
        .background(Color.white)
        .environment(appModel())
        .environment(\.dynamicTypeSize, size)

        let renderer = ImageRenderer(content: rows)
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.uiImage, "\(name) did not draw")
        let attachment = XCTAttachment(image: image)
        attachment.name = "queue-\(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
        return image.size
    }

    func testEveryStateDrawsAndTheLargestTextGrowsTheRowsInsteadOfCuttingThem() async throws {
        let model = await model()
        XCTAssertEqual(model.items.count, 5)
        let regular = try draw(model, size: .large, canControl: true, name: "default")
        let largest = try draw(model, size: .accessibility3, canControl: true, name: "ax3")
        XCTAssertEqual(regular.width, 390, accuracy: 1)
        XCTAssertEqual(largest.width, 390, accuracy: 1, "wider than the phone is cut off")
        XCTAssertGreaterThan(regular.height, 200, "five rows should not draw as nothing")
        XCTAssertGreaterThan(
            largest.height, regular.height * 1.5,
            "at accessibility sizes the rows wrap and grow"
        )
    }

    func testAChatOnlyPairingDrawsTheSameRowsWithoutCancelRender() async throws {
        let model = await model()
        let chatOnly = try draw(model, size: .large, canControl: false, name: "chat-only")
        XCTAssertEqual(chatOnly.width, 390, accuracy: 1)
        XCTAssertGreaterThan(chatOnly.height, 200)
        for row in model.items {
            XCTAssertFalse(
                model.controls(for: row, canControl: false).contains { $0.verb == .cancelRender },
                "\(row.id) offers Cancel render to a chat-only pairing"
            )
        }
    }
}
