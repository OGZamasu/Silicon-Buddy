import XCTest
@testable import SiliconBuddy

/// What happens between a question and an answer, including the part where the Mac has
/// not shipped streaming yet.
@MainActor
final class ChatModelTests: XCTestCase {

    private func makeModel() -> (ChatModel, URL) {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString)
        return (ChatModel(store: ConversationStore(directory: directory)), directory)
    }

    private func waitForIdle(_ model: ChatModel) async throws {
        for _ in 0..<200 {
            if !model.isSending { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The model never stopped sending")
    }

    func testAStreamedReplyArrivesTokenByToken() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamEvents = [
            .reasoning("Thinking about it."),
            .token("Hello"),
            .token(", world"),
            .finished(BuddyAPI.ChatMetrics(promptTokens: 8, generatedTokens: 3, tokensPerSecond: 21)),
        ]
        model.draft = "Say hello"
        model.send(using: transport)
        try await waitForIdle(model)

        let reply = try XCTUnwrap(model.current?.messages.last)
        XCTAssertEqual(reply.role, .assistant)
        XCTAssertEqual(reply.content, "Hello, world")
        XCTAssertEqual(reply.reasoning, "Thinking about it.")
        XCTAssertEqual(reply.metrics?.generatedTokens, 3)
        XCTAssertFalse(reply.isStreaming)
        XCTAssertEqual(transport.chatCallCount, 0, "Streaming worked; /chat should not be called")
    }

    func testAMacWithConversationsIsAskedToAppendRatherThanResendTheHistory() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.streamEvents = [.token("Noted.")]
        await model.loadConversations(using: transport)
        XCTAssertTrue(model.usesRemoteConversations)

        model.draft = "Remember this"
        model.send(using: transport)
        try await waitForIdle(model)
        XCTAssertEqual(model.current?.messages.last?.content, "Noted.")
        XCTAssertEqual(transport.lastSentMessage?.content, "Remember this")
    }

    func testAMacWithoutStreamingFallsBackToPlainChat() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamEvents = nil // 404 from /chat/stream
        transport.chatResult = .success(
            ControlAPI.ChatResponse(
                content: "Hello there.", reasoning: nil, promptTokens: 8,
                generatedTokens: 3, tokensPerSecond: 19.5
            )
        )
        model.draft = "Say hello"
        model.send(using: transport)
        try await waitForIdle(model)

        XCTAssertEqual(model.current?.messages.last?.content, "Hello there.")
        XCTAssertEqual(transport.chatCallCount, 1)
        XCTAssertFalse(model.usesStreaming, "The fallback should be remembered, not rediscovered")

        // A second message must not ask the missing routes again.
        let streamAttempts = transport.streamCallCount
        model.draft = "Again"
        model.send(using: transport)
        try await waitForIdle(model)
        XCTAssertEqual(transport.streamCallCount, streamAttempts, "No more SSE attempts")
        XCTAssertEqual(transport.chatCallCount, 2)
    }

    func testARealFailureIsShownRatherThanRetried() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamError = TransportError.badRequest("No model is loaded.")
        model.draft = "Hi"
        model.send(using: transport)
        try await waitForIdle(model)

        XCTAssertEqual(transport.chatCallCount, 0, "A 400 is an answer, not a missing route")
        XCTAssertEqual(model.current?.messages.last?.failure, "No model is loaded.")
        XCTAssertNotNil(model.error)
    }

    func testTheWholeHistoryIsSent() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamEvents = [.token("one")]
        // A Mac that streams but does not store conversations: the phone sends the
        // history, because nothing on the other end remembers it.
        await model.loadConversations(using: transport)
        model.draft = "first"
        model.send(using: transport)
        try await waitForIdle(model)
        model.draft = "second"
        model.send(using: transport)
        try await waitForIdle(model)

        let sent = try XCTUnwrap(transport.lastChatRequest)
        XCTAssertEqual(sent.messages.map(\.role), ["user", "assistant", "user"])
        XCTAssertEqual(sent.messages.last?.content, "second")
    }

    func testAnAttachmentIsSentAsADataURL() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamEvents = [.token("A cat.")]
        await model.loadConversations(using: transport)
        model.attachments = [ChatAttachment(jpeg: Data([0xFF, 0xD8, 0xFF]))]
        model.draft = "What is this?"
        model.send(using: transport)
        try await waitForIdle(model)

        let image = try XCTUnwrap(transport.lastChatRequest?.messages.last?.images.first)
        XCTAssertTrue(image.hasPrefix("data:image/jpeg;base64,"))
        XCTAssertTrue(model.attachments.isEmpty, "Attachments are consumed by sending")
    }

    func testAnEmptyDraftSendsNothing() {
        let (model, _) = makeModel()
        let transport = StubTransport()
        model.draft = "   "
        model.send(using: transport)
        XCTAssertNil(model.current)
        XCTAssertEqual(transport.streamCallCount, 0)
    }

    func testConversationsFallBackToTheDeviceWhenTheMacHasNone() async {
        let (model, _) = makeModel()
        let transport = StubTransport()
        await model.loadConversations(using: transport)
        XCTAssertFalse(model.usesRemoteConversations)
        XCTAssertTrue(model.storageNote.contains("this device"))
    }

    func testAConversationIsKeptOnTheDevice() async throws {
        let (model, directory) = makeModel()
        let transport = StubTransport()
        transport.streamEvents = [.token("Kept.")]
        await model.loadConversations(using: transport)
        model.draft = "Remember this"
        model.send(using: transport)
        try await waitForIdle(model)

        let reopened = ChatModel(store: ConversationStore(directory: directory))
        await reopened.loadConversations(using: nil)
        XCTAssertEqual(reopened.conversations.count, 1)
        XCTAssertEqual(reopened.conversations.first?.title, "Remember this")
        XCTAssertEqual(reopened.conversations.first?.messageCount, 2)
    }
}
