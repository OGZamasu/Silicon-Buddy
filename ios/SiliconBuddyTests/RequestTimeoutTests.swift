import XCTest
@testable import SiliconBuddy

/// How long a request may take, and what ends one that has gone quiet.
///
/// On `URLSession.buddy` a request's own timeout is an idle timer — reset by every byte —
/// and it is the only limit once the whole-transfer cap is gone. So every route has to
/// say one, and a long-lived stream has to say one short enough to notice a Mac that has
/// gone without closing it.
final class RequestTimeoutTests: XCTestCase {

    override func tearDown() {
        RecordingProtocol.reset()
        super.tearDown()
    }

    /// Every route the client has, asked of a session that records the request and fails it.
    private func recordEveryRoute() async -> [URLRequest] {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RecordingProtocol.self]
        configuration.timeoutIntervalForRequest = 30
        let client = ControlClient(
            config: ServerConfig(host: "100.64.0.9", port: 8788, token: "device-token"),
            session: URLSession(configuration: configuration)
        )
        let load = ControlAPI.LoadRequest(modelID: "x")
        let chat = ControlAPI.ChatRequest(messages: [.init(role: "user", content: "hi")])
        _ = try? await client.health()
        _ = try? await client.status()
        _ = try? await client.profile()
        _ = try? await client.metrics()
        _ = try? await client.installed()
        _ = try? await client.catalog(category: nil, onlyRunnable: false)
        _ = try? await client.swarm()
        _ = try? await client.node()
        _ = try? await client.videoModels()
        _ = try? await client.imageModels()
        _ = try? await client.videoQueue()
        _ = try? await client.controlVideoQueue(
            ControlAPI.VideoQueueControl(action: ControlAPI.VideoQueueControl.cancel, id: "clip")
        )
        _ = try? await client.load(load)
        _ = try? await client.install(load)
        _ = try? await client.unload()
        _ = try? await client.chat(chat)
        _ = try? await client.pair(code: "123456", deviceName: "iPad", platform: "ipados")
        _ = try? await client.conversations()
        _ = try? await client.createConversation(title: nil)
        _ = try? await client.conversation(id: "C1")
        for stream in [
            client.chatStream(chat),
            client.sendMessage(conversationID: "C1", message: .init(role: "user", content: "hi"), maxTokens: nil),
        ] {
            do { for try await _ in stream {} } catch {}
        }
        // `/events` reconnects rather than failing, so it is read until it has asked once.
        let events = Task { for try await _ in client.events() {} }
        for _ in 0..<200 where !RecordingProtocol.requests.contains(where: { $0.url?.path == "/events" }) {
            try? await Task.sleep(for: .milliseconds(10))
        }
        events.cancel()
        return RecordingProtocol.requests
    }

    /// 60 seconds is URLRequest's default, and URLSession reads it as "not set" and uses
    /// the session's 30 — `/install` was asking for a minute and getting half of one.
    func testNoRouteAsksForExactlySixtySeconds() async {
        let requests = await recordEveryRoute()
        XCTAssertGreaterThanOrEqual(Set(requests.compactMap { $0.url?.path }).count, 20)
        for request in requests {
            XCTAssertNotEqual(
                request.timeoutInterval, 60,
                "\(request.url?.path ?? "?") would get the session's 30 s instead"
            )
        }
        let install = requests.first { $0.url?.path == "/install" }
        XCTAssertGreaterThanOrEqual(install?.timeoutInterval ?? 0, 60)
    }

    /// With no cap on the whole transfer, `/events`' own timeout is all that notices a Mac
    /// that went away without closing it. A day of silence is a day of a frozen "Happening
    /// now"; a few missed heartbeats (the Mac sends one every 15 s) is enough.
    func testTheEventStreamNoticesAQuietMacWithinAFewHeartbeats() async {
        let requests = await recordEveryRoute()
        let events = requests.first { $0.url?.path == "/events" }
        let timeout = events?.timeoutInterval ?? .infinity
        XCTAssertLessThanOrEqual(timeout, 45, "At most three missed heartbeats")
        XCTAssertGreaterThan(timeout, 15, "More than one heartbeat, or a healthy stream drops")
    }

    /// And when it does notice, the stream is opened again rather than left for dead.
    func testASilentEventStreamIsOpenedAgain() async throws {
        let mac = try StallServer(frames: 1, every: 0, thenClose: false)
        defer { mac.stop() }
        let client = ControlClient(
            config: ServerConfig(host: "127.0.0.1", port: mac.port, token: "device-token")
        )
        let reading = Task { for try await _ in client.events(idleTimeout: 1) {} }
        defer { reading.cancel() }
        let deadline = Date().addingTimeInterval(10)
        while mac.connections < 2, Date() < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertGreaterThanOrEqual(mac.connections, 2, "The quiet stream was never replaced")
    }

    /// The idle timer is reset by every byte: a stream that keeps talking is never cut,
    /// however long it runs.
    func testAStreamThatKeepsTalkingIsNotCutForTakingLong() async throws {
        let mac = try StallServer(frames: 6, every: 0.5, thenClose: true)
        defer { mac.stop() }
        var request = URLRequest(
            url: try XCTUnwrap(URL(string: "http://127.0.0.1:\(mac.port)/events")),
            timeoutInterval: 1
        )
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let (bytes, _) = try await URLSession.buddy.bytes(for: request)
        var lines = 0
        for try await _ in bytes.lines { lines += 1 }
        XCTAssertGreaterThanOrEqual(lines, 6, "Three seconds under a one-second idle timeout")
    }
}

/// Records what the client asks for and fails it at once: nothing leaves the test.
final class RecordingProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var seen: [URLRequest] = []

    static var requests: [URLRequest] { lock.withLock { seen } }
    static func reset() { lock.withLock { seen = [] } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.withLock { Self.seen.append(request) }
        client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
    }

    override func stopLoading() {}
}
