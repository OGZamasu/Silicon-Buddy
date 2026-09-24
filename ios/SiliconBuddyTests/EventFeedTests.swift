import XCTest
@testable import SiliconBuddy

/// What "Happening now" and the Models list are told, and when something stops being news.
@MainActor
final class EventFeedTests: XCTestCase {

    /// Runs the feed over what the Mac sent, to the end of the stream.
    private func feed(_ events: [BuddyAPI.ServerEvent]) async throws -> EventFeed {
        let transport = StubTransport()
        transport.serverEvents = events
        let feed = EventFeed()
        feed.start(using: transport)
        for _ in 0..<200 {
            if feed.lastEvent != nil, !feed.isLive { return feed }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The feed never finished reading")
        return feed
    }

    /// "completed" is the word the Mac uses for a finished render.
    func testAFinishedRenderStopsBeingNews() async throws {
        let feed = try await feed([
            .job(.init(id: "9C2F-0001", kind: "video", status: "rendering", fraction: 0.5)),
            .job(.init(id: "9C2F-0002", kind: "video", status: "pending")),
            .job(.init(id: "9C2F-0001", kind: "video", status: "completed", mediaID: "bWVkaWE")),
            .job(.init(id: "9C2F-0002", kind: "video", status: "rendering")),
        ])
        XCTAssertEqual(Set(feed.jobs.keys), ["9C2F-0002"])
    }

    func testAFailedOrCancelledRenderStopsBeingNews() async throws {
        let feed = try await feed([
            .job(.init(id: "a", kind: "video", status: "rendering")),
            .job(.init(id: "b", kind: "video", status: "rendering")),
            .job(.init(id: "a", kind: "video", status: "failed", reason: "Out of memory.")),
            .job(.init(id: "b", kind: "video", status: "cancelled")),
        ])
        XCTAssertTrue(feed.jobs.isEmpty)
    }

    /// A download that failed, or was removed on the Mac, is over — it is not a transfer
    /// stuck at nought that hides the model's Load button for good.
    func testAFailedDownloadStopsBeingNews() async throws {
        let feed = try await feed([
            .download(.init(
                id: "gemma-4-e4b@Q4_K_M", name: "Gemma 4 E4B", bytesReceived: 1_000,
                bytesExpected: 10_000, fraction: 0.1
            )),
            .download(.init(
                id: "gemma-4-e4b@Q4_K_M", name: "Gemma 4 E4B", bytesReceived: 0,
                bytesExpected: 10_000, fraction: 0, error: "The Mac's disk is full."
            )),
        ])
        XCTAssertTrue(feed.downloads.isEmpty)
        XCTAssertNil(feed.download(forModel: "gemma-4-e4b@Q4_K_M"))
    }

    /// The Models list asks by the installed id, which carries its quantization. Another
    /// quantization of the same model downloading is not this one downloading.
    func testAnotherQuantizationsDownloadIsNotThisModels() async throws {
        let feed = try await feed([
            .download(.init(
                id: "qwen3-coder-30b@Q8_0", name: "Qwen3-Coder 30B A3B", bytesReceived: 1_000,
                bytesExpected: 30_000, fraction: 0.03
            )),
        ])
        XCTAssertNil(feed.download(forModel: "qwen3-coder-30b@Q4_K_M"))
        XCTAssertNotNil(feed.download(forModel: "qwen3-coder-30b@Q8_0"))
        XCTAssertNotNil(feed.download(forModel: "qwen3-coder-30b"), "A bare id still finds it")
    }
}
