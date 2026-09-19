import XCTest
@testable import SiliconBuddy

final class ControlClientTests: XCTestCase {

    private var server: LoopbackServer!
    private var client: ControlClient!

    override func setUpWithError() throws {
        try super.setUpWithError()
        server = try LoopbackServer()
        client = ControlClient(
            config: ServerConfig(host: "127.0.0.1", port: server.port, token: "device-token")
        )
    }

    override func tearDown() {
        server.stop()
        server = nil
        client = nil
        super.tearDown()
    }

    // MARK: - Pairing against a Mac that has no pairing

    /// The fallback that never fired. An unknown route answers 401 to a caller with no
    /// token and 404 only to one with a good one — and `/buddy/pair` is the one call
    /// made without a token. A Mac that *has* the route can never 401 it.
    func testAPreM0MacAnswers401ToPairingAndThatMeansTheRouteIsMissing() async {
        server.reply("/buddy/pair", 401, #"{"error":"Invalid or missing control token."}"#)
        do {
            _ = try await client.pair(code: "123456", deviceName: "iPad", platform: "ipados")
            XCTFail("Expected the route to be reported as missing")
        } catch let error as TransportError {
            XCTAssertEqual(error, .routeUnavailable("/buddy/pair"))
            XCTAssertTrue(
                error.isMissingRoute,
                "This is what makes the app offer the advanced form instead"
            )
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    func testA404OnPairingAlsoMeansTheRouteIsMissing() async {
        server.reply("/buddy/pair", 404, #"{"error":"Unknown endpoint"}"#)
        do {
            _ = try await client.pair(code: "123456", deviceName: "iPad", platform: "ipados")
            XCTFail("Expected the route to be reported as missing")
        } catch let error as TransportError {
            XCTAssertTrue(error.isMissingRoute)
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    func testAWrongPairingCodeIsRefusedWithoutClaimingTheRouteIsMissing() async {
        server.reply("/buddy/pair", 403, #"{"error":"That pairing code is not the one on screen."}"#)
        do {
            _ = try await client.pair(code: "000000", deviceName: "iPad", platform: "ipados")
            XCTFail("Expected a refusal")
        } catch let error as TransportError {
            XCTAssertFalse(error.isMissingRoute, "A wrong code is not a missing route")
            XCTAssertTrue(error.isForbidden)
            XCTAssertEqual(
                error.errorDescription, "That pairing code is not the one on screen."
            )
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    func testPairingCarriesTheScopeTheMacGranted() async throws {
        server.reply("/buddy/pair", 200, """
        {"deviceID":"D1","token":"t","macName":"Mac Studio","port":8788,"scope":"chat"}
        """)
        let paired = try await client.pair(
            code: "123456", deviceName: "iPad", platform: "ipados"
        )
        XCTAssertEqual(BuddyAPI.DeviceScope(wire: paired.scope), .chat)
    }

    // MARK: - What the server refuses

    func testAChatScopeDeviceIsToldWhyLoadingWasRefused() async {
        server.reply("/load", 403, """
        {"error":"This device is paired for chat only."}
        """)
        do {
            _ = try await client.load(ControlAPI.LoadRequest(modelID: "x"))
            XCTFail("Expected a refusal")
        } catch let error as TransportError {
            XCTAssertEqual(error, .forbidden("This device is paired for chat only."))
            XCTAssertNotEqual(error, .unauthorized, "403 is not 'pair again'")
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    func testAConversationThatIsStillBeingAnsweredIsAConflict() async {
        server.reply("/conversations/C1/messages", 409, """
        {"error":"That conversation is still being answered."}
        """)
        var events: [BuddyAPI.ChatStreamEvent] = []
        do {
            for try await event in client.sendMessage(
                conversationID: "C1",
                message: .init(role: "user", content: "hi"),
                maxTokens: 2048
            ) {
                events.append(event)
            }
            XCTFail("Expected a conflict")
        } catch let error as TransportError {
            guard case .conflict(let message) = error else {
                return XCTFail("Expected .conflict, got \(error)")
            }
            XCTAssertTrue(message.contains("still being answered"))
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
        XCTAssertTrue(events.isEmpty)
    }

    func testTooMuchToSendIsItsOwnError() async {
        server.reply("/chat", 413, """
        {"error":"That request body is larger than this device may send (4194304 bytes)."}
        """)
        do {
            _ = try await client.chat(ControlAPI.ChatRequest(messages: []))
            XCTFail("Expected a refusal")
        } catch let error as TransportError {
            guard case .tooLarge = error else {
                return XCTFail("Expected .tooLarge, got \(error)")
            }
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    // MARK: - What the client sends

    func testTheConversationRouteSendsOneMessageNotATranscript() async throws {
        server.events("/conversations/C1/messages", """
        event: token
        data: {"text":"Hi"}

        event: finished
        data: {"promptTokens":4,"generatedTokens":1,"tokensPerSecond":12.5,"timeToFirstToken":0.2}

        """)
        var text = ""
        var metrics: BuddyAPI.ChatMetrics?
        for try await event in client.sendMessage(
            conversationID: "C1",
            message: .init(role: "user", content: "Hello", images: ["data:image/jpeg;base64,AAA"]),
            maxTokens: 512
        ) {
            switch event {
            case .token(let piece): text += piece
            case .finished(let received): metrics = received
            default: break
            }
        }
        XCTAssertEqual(text, "Hi")
        XCTAssertEqual(metrics?.timeToFirstToken, 0.2)

        let body = try XCTUnwrap(server.request(to: "/conversations/C1/messages")?.body)
        let json = try JSONValue(data: body)
        XCTAssertEqual(json["content"], .string("Hello"))
        XCTAssertEqual(json["images"]?[0], .string("data:image/jpeg;base64,AAA"))
        XCTAssertEqual(json["maxTokens"], .number(512))
        XCTAssertNil(json["messages"], "That is the /chat shape, not this one")
    }

    func testTheEventStreamDecodesTheMacsFieldNames() async throws {
        server.events("/events", """
        event: status
        data: {"state":"running","loadedModelID":"qwen3-coder-30b","expertStreaming":false}

        event: download
        data: {"id":"qwen3-coder-30b","name":"Qwen3-Coder 30B A3B","bytesReceived":8589934592,"bytesExpected":20401094656,"bytesPerSecond":41943040,"fraction":0.42}

        event: job
        data: {"id":"9C2F-0001","kind":"video","status":"running","fraction":0.33,"title":"Opening shot"}

        event: heartbeat
        data: {"at":"2026-09-18T09:41:00Z"}

        """)
        var seen: [BuddyAPI.ServerEvent] = []
        for try await event in client.events() {
            seen.append(event)
            if seen.count == 4 { break }
        }
        guard seen.count == 4 else { return XCTFail("Expected four events, got \(seen.count)") }

        if case .status(let status) = seen[0] {
            XCTAssertEqual(status.loadedModelID, "qwen3-coder-30b")
        } else { XCTFail("First event should be a status") }

        if case .download(let download) = seen[1] {
            XCTAssertEqual(download.id, "qwen3-coder-30b")
            XCTAssertEqual(download.name, "Qwen3-Coder 30B A3B")
            XCTAssertEqual(download.bytesReceived, 8_589_934_592)
            XCTAssertEqual(download.progress, 0.42)
        } else { XCTFail("Second event should be a download") }

        if case .job(let job) = seen[2] {
            XCTAssertEqual(job.status, "running")
            XCTAssertEqual(job.title, "Opening shot")
            XCTAssertEqual(job.fraction, 0.33)
        } else { XCTFail("Third event should be a job") }

        if case .heartbeat(let at) = seen[3] {
            XCTAssertNotNil(at, "The Mac's heartbeat carries its clock")
        } else { XCTFail("Fourth event should be a heartbeat") }
    }

    func testAMacWithoutEventsSaysSoOnceRatherThanRetrying() async {
        server.reply("/events", 404, #"{"error":"Unknown endpoint"}"#)
        do {
            for try await _ in client.events() {
                XCTFail("There should be no events")
            }
            XCTFail("Expected the stream to report the missing route")
        } catch let error as TransportError {
            XCTAssertTrue(error.isMissingRoute, "This is what sends the screens back to polling")
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
    }

    // MARK: - The host rule, one layer down

    func testTheClientRefusesAHostOutsideTheTailnetEvenIfOneGetsIn() async {
        let rogue = ControlClient(
            config: ServerConfig(host: "evil.example.com", port: server.port, token: "t")
        )
        do {
            _ = try await rogue.status()
            XCTFail("That host should never be dialled")
        } catch let error as TransportError {
            XCTAssertTrue(error.isForbidden)
        } catch {
            XCTFail("Expected a TransportError, got \(error)")
        }
        XCTAssertTrue(server.requests.isEmpty, "Nothing should have left the app")
    }
}
