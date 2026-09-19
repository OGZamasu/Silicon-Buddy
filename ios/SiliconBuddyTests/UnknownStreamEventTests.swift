import XCTest
@testable import SiliconBuddy

/// An event name this build has never heard of must be skipped, not fatal.
///
/// The Mac grows them — `verdict` arrived with Jev's answer checking, and more will —
/// and a phone that treats one as an error breaks on the upgrade rather than on the
/// downgrade. The `default:` arms that do this were unexercised: the critic mutated
/// them to fail and the whole suite still passed, which is what this file exists for.
final class UnknownStreamEventTests: XCTestCase {

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

    private var request: ControlAPI.ChatRequest {
        ControlAPI.ChatRequest(
            messages: [ControlAPI.ChatRequest.Message(role: "user", content: "hello")]
        )
    }

    /// Two strangers in the middle of an answer: the `verdict` the Mac really sends,
    /// and one nobody has invented yet.
    func testTheChatStreamSkipsNamesItDoesNotKnow() async throws {
        server.events("/chat/stream", """
        event: token
        data: {"text":"A "}

        event: verdict
        data: {"verdict":"annotate","reasons":["a reason"]}

        event: weather
        data: {"outlook":"fine"}

        event: token
        data: {"text":"tailnet."}

        event: finished
        data: {"promptTokens":4,"generatedTokens":2,"tokensPerSecond":9.5}

        """)

        var text = ""
        var finished = false
        for try await event in client.chatStream(request) {
            switch event {
            case .token(let piece): text += piece
            case .finished: finished = true
            case .failed(let message): XCTFail("the stream failed: \(message)")
            case .reasoning: break
            }
        }
        XCTAssertEqual(text, "A tailnet.", "the tokens either side of the strangers survived")
        XCTAssertTrue(finished, "and the answer still ended")
    }

    /// The same, on the conversation route, which is the one the app prefers.
    func testTheConversationStreamSkipsNamesItDoesNotKnow() async throws {
        server.events("/conversations/C1/messages", """
        event: token
        data: {"text":"Yes"}

        event: somethingNew
        data: {"whatever":1}

        event: finished
        data: {"promptTokens":1,"generatedTokens":1,"tokensPerSecond":1}

        """)

        var text = ""
        for try await event in client.sendMessage(
            conversationID: "C1",
            message: ControlAPI.ChatRequest.Message(role: "user", content: "?"),
            maxTokens: 64
        ) {
            if case .token(let piece) = event { text += piece }
        }
        XCTAssertEqual(text, "Yes")
    }

    /// `GET /events` has the same rule and a different `default:` arm.
    func testTheEventStreamSkipsNamesItDoesNotKnow() async throws {
        server.events("/events", """
        event: aurora
        data: {"colour":"green"}

        event: status
        data: {"state":"Ready","expertStreaming":false}

        event: heartbeat
        data: {}

        """, chunked: false)

        var sawStatus = false
        for try await event in client.events() {
            if case .status = event { sawStatus = true }
            if case .heartbeat = event { break }
        }
        XCTAssertTrue(sawStatus, "the status after the stranger still arrived")
    }

    /// And the one that is not a stranger any more, so the two rules are visibly
    /// different: `verdict` is decoded on `/events`, and skipped mid-answer.
    func testTheEventStreamDecodesAVerdict() async throws {
        server.events("/events", """
        event: verdict
        data: {"conversationID":"C1","messageID":"m4","verdict":"escalate","reasons":["r"]}

        event: heartbeat
        data: {}

        """, chunked: false)

        var checked: BuddyAPI.Verdict?
        for try await event in client.events() {
            if case .verdict(let verdict) = event { checked = verdict }
            if case .heartbeat = event { break }
        }
        XCTAssertEqual(checked?.messageID, "m4")
        XCTAssertEqual(checked?.verdict, "escalate")
    }
}
