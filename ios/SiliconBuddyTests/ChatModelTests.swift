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

    func testAConversationTheMacIsStillAnsweringClosesTheComposer() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.conversationError = TransportError.conflict(
            "That conversation is still being answered."
        )
        await model.loadConversations(using: transport)
        model.draft = "Another one"
        model.send(using: transport)
        try await waitForIdle(model)

        XCTAssertTrue(model.isConversationBusy, "The composer closes rather than being refused")
        XCTAssertEqual(transport.chatCallCount, 0, "A 409 is an answer, not a missing route")
        XCTAssertEqual(
            model.current?.messages.last?.failure, "That conversation is still being answered."
        )
    }

    func testOpeningAConversationTheMacIsAnsweringClosesTheComposer() async {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.conversationDetail = BuddyAPI.ConversationDetail(
            id: "C1", title: "Busy one", updatedAt: Date(), isGenerating: true,
            messages: [.init(role: "user", content: "hi", createdAt: Date())]
        )
        await model.loadConversations(using: transport)
        await model.open(id: "C1", using: transport)
        XCTAssertTrue(model.isConversationBusy)
    }

    func testTheConversationRouteIsGivenTheTokenCeiling() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.streamEvents = [.token("ok")]
        await model.loadConversations(using: transport)
        model.draft = "Hello"
        model.send(using: transport)
        try await waitForIdle(model)
        XCTAssertEqual(transport.lastSentMaxTokens, model.maxTokens)
    }

    func testStopBeforeTheFirstTokenLeavesTheMacsRoutesAlone() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.streamHangs = true
        await model.loadConversations(using: transport)
        XCTAssertTrue(model.usesRemoteConversations)

        model.draft = "Think hard about this"
        model.send(using: transport)
        let running = try XCTUnwrap(model.sendTask)
        for _ in 0..<200 where transport.streamCallCount == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(transport.streamCallCount, 1, "The conversation route was asked")
        model.cancel()
        await running.value

        XCTAssertTrue(model.usesRemoteConversations, "Stop is not a Mac without conversations")
        XCTAssertTrue(model.usesStreaming, "Stop is not a Mac without streaming")
        XCTAssertEqual(transport.streamCallCount, 1, "A stopped reply tries no other route")
        XCTAssertEqual(transport.chatCallCount, 0)
        XCTAssertEqual(model.current?.messages.last?.failure, "Stopped.")
        XCTAssertNil(model.error)
    }

    /// The Mac writes its 200 only once the answer has started, and ends the body by
    /// closing the connection. A Mac that quits or gives up before the first token therefore
    /// answers 200 with nothing in it — a route that is there, saying nothing.
    func testASilentAnswerIsNotAMacWithoutConversationsOrStreaming() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.streamEvents = []
        transport.chatResult = .success(
            ControlAPI.ChatResponse(
                content: "Answered on /chat.", reasoning: nil, promptTokens: 1,
                generatedTokens: 3, tokensPerSecond: 10
            )
        )
        await model.loadConversations(using: transport)
        XCTAssertTrue(model.usesRemoteConversations)

        model.draft = "hello"
        model.send(using: transport)
        try await waitForIdle(model)

        XCTAssertTrue(model.usesRemoteConversations, "Silence is not a Mac that keeps no conversations")
        XCTAssertTrue(model.usesStreaming, "Nor one that cannot stream")
        XCTAssertEqual(transport.streamCallCount, 2, "The conversation route, then plain streaming")
        XCTAssertEqual(transport.chatCallCount, 1, "And /chat, for this reply")
        XCTAssertEqual(model.current?.messages.last?.content, "Answered on /chat.")
    }

    func testARePairWhileWaitingLeavesTheNewMacsRoutesAlone() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([])
        transport.streamHangs = true
        await model.loadConversations(using: transport)

        model.draft = "Still thinking"
        model.send(using: transport)
        let running = try XCTUnwrap(model.sendTask)
        for _ in 0..<200 where transport.streamCallCount == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }
        model.macChanged()
        await running.value

        // The next Mac starts from "not asked yet", not from what the last one's
        // cancelled reply concluded about it.
        XCTAssertTrue(model.usesStreaming)
        XCTAssertEqual(transport.chatCallCount, 0)
        XCTAssertNil(model.current)
    }

    /// A widget's "Ask" on a cold start makes its conversation here before the Mac has
    /// said it keeps them; a thread open here can be deleted on the Mac. Either way the
    /// Mac answers 404 about that one conversation, and still keeps all the others.
    func testAConversationTheMacHasNeverHeardOfStaysHereAndTheRestStaySynced() async throws {
        let (model, directory) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([
            BuddyAPI.ConversationSummary(id: "M1", title: "On the Mac", updatedAt: Date(), messageCount: 2)
        ])
        transport.conversationError = TransportError.notFound(
            "That conversation isn't on your Mac any more."
        )
        transport.streamEvents = [.token("Answered anyway.")]
        await model.loadConversations(using: transport)
        XCTAssertTrue(model.usesRemoteConversations)

        model.draft = "Hello"
        model.send(using: transport)
        try await waitForIdle(model)

        XCTAssertTrue(model.usesRemoteConversations, "One unknown thread is not a Mac without conversations")
        XCTAssertTrue(model.usesStreaming)
        XCTAssertEqual(model.current?.messages.last?.content, "Answered anyway.")
        XCTAssertNil(model.current?.messages.last?.failure)
        XCTAssertNil(model.error)
        XCTAssertEqual(transport.streamCallCount, 2, "The conversation route, then plain streaming")
        XCTAssertEqual(transport.lastChatRequest?.messages.last?.content, "Hello")
        XCTAssertTrue(model.storageNote.contains("this device"))
        let listed = Set(model.conversations.map(\.id))
        XCTAssertTrue(listed.contains("M1"), "The Mac's conversations are still listed")
        XCTAssertTrue(listed.contains(try XCTUnwrap(model.current?.id)), "So is this one")

        // Kept here from now on: the next message goes straight to plain streaming, and
        // the transcript is the device's.
        model.draft = "Again"
        model.send(using: transport)
        try await waitForIdle(model)
        XCTAssertEqual(transport.streamCallCount, 3)
        XCTAssertEqual(transport.lastChatRequest?.messages.map(\.content), ["Hello", "Answered anyway.", "Again"])
        let stored = await ConversationStore(directory: directory).conversation(id: try XCTUnwrap(model.current?.id))
        XCTAssertEqual(stored?.messages.count, 4)

        // A conversation the Mac does have still goes to the Mac.
        transport.conversationError = nil
        transport.conversationDetail = BuddyAPI.ConversationDetail(
            id: "M1", title: "On the Mac", updatedAt: Date(), isGenerating: false,
            messages: [.init(role: "user", content: "hi", createdAt: Date())]
        )
        await model.open(id: "M1", using: transport)
        model.draft = "To the Mac"
        model.send(using: transport)
        try await waitForIdle(model)
        XCTAssertEqual(transport.lastSentMessage?.content, "To the Mac")
        XCTAssertEqual(model.storageNote, "Synced with the Mac.")
    }

    /// A question asked while the last answer is still arriving — push-to-talk stays live
    /// while the Mac answers. The new send replaces the old one, and nothing of the old
    /// one's ending may land on the new reply.
    func testASecondQuestionWhileTheFirstIsArrivingIsNotEndedByTheFirst() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamHangs = true
        model.draft = "First question"
        model.send(using: transport)
        let first = try XCTUnwrap(model.sendTask)
        for _ in 0..<200 where transport.streamCallCount == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }

        model.draft = "Second question"
        model.send(using: transport)
        let second = try XCTUnwrap(model.sendTask)
        await first.value
        for _ in 0..<200 where transport.streamCallCount < 2 {
            try await Task.sleep(for: .milliseconds(10))
        }

        let messages = try XCTUnwrap(model.current?.messages)
        XCTAssertEqual(messages.map(\.role), [.user, .assistant, .user, .assistant])
        XCTAssertEqual(messages[1].failure, "Stopped.", "The first reply is closed as stopped")
        XCTAssertFalse(messages[1].isStreaming)
        XCTAssertTrue(messages[3].isStreaming, "The new reply is still arriving")
        XCTAssertNil(messages[3].failure)
        XCTAssertTrue(model.isSending, "The composer stays closed while the new answer arrives")
        XCTAssertNotNil(model.sendingSince)
        XCTAssertEqual(transport.streamCallCount, 2, "The first send tries no other route")
        XCTAssertEqual(transport.chatCallCount, 0)

        model.cancel()
        await second.value
    }

    /// A finished answer's last step is reading the Mac's list again, and the send is still
    /// "sending" while it does. A question asked then replaces it, and cancelling that read
    /// must not leave "Cancelled." standing under the new question.
    func testASendReplacedWhileItRereadsTheListLeavesNoErrorBehind() async throws {
        let mac = try SlowListServer(delay: 3)
        defer { mac.stop() }
        let client = ControlClient(
            config: ServerConfig(host: "127.0.0.1", port: mac.port, token: "device-token")
        )
        let (model, _) = makeModel()
        await model.loadConversations(using: client)
        XCTAssertTrue(model.usesRemoteConversations)

        model.draft = "First question"
        model.send(using: client)
        let first = try XCTUnwrap(model.sendTask)
        for _ in 0..<300 where mac.count("GET /conversations") < 2 {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(mac.count("GET /conversations"), 2, "The first send is reading the list")

        model.draft = "Second question"
        model.send(using: client)
        let second = try XCTUnwrap(model.sendTask)
        await first.value
        XCTAssertNil(model.error, "The replaced send's cancelled read surfaced as an error")
        XCTAssertEqual(model.current?.messages.map(\.content).prefix(3), ["First question", "A", "Second question"])

        model.cancel()
        await second.value
        XCTAssertNil(model.error)
    }

    func testTheAnswerToTheSecondQuestionIsTheOneThatLands() async throws {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.streamHangs = true
        model.draft = "First question"
        model.send(using: transport)
        let first = try XCTUnwrap(model.sendTask)
        for _ in 0..<200 where transport.streamCallCount == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }

        transport.streamHangs = false
        transport.streamEvents = [
            .token("Second answer."),
            .finished(BuddyAPI.ChatMetrics(promptTokens: 8, generatedTokens: 3, tokensPerSecond: 20)),
        ]
        model.draft = "Second question"
        model.send(using: transport)
        await first.value
        try await waitForIdle(model)

        let messages = try XCTUnwrap(model.current?.messages)
        XCTAssertEqual(messages[1].failure, "Stopped.")
        XCTAssertEqual(messages[3].content, "Second answer.")
        XCTAssertNil(messages[3].failure)
        XCTAssertFalse(messages[3].isStreaming)
        XCTAssertNil(model.error)
    }

    /// The conversation a 404 left on the phone is in the device's store. After a restart
    /// nothing else remembers it, so it has to come back with the list — and stay the
    /// device's, not be asked of the Mac again.
    func testAConversationKeptHereIsStillListedAfterARestart() async throws {
        let (model, directory) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .success([
            BuddyAPI.ConversationSummary(id: "M1", title: "On the Mac", updatedAt: Date(), messageCount: 2)
        ])
        transport.conversationError = TransportError.notFound(
            "That conversation isn't on your Mac any more."
        )
        transport.streamEvents = [.token("Kept.")]
        await model.loadConversations(using: transport)
        model.draft = "Hello"
        model.send(using: transport)
        try await waitForIdle(model)
        let kept = try XCTUnwrap(model.current?.id)

        let relaunched = ChatModel(store: ConversationStore(directory: directory))
        await relaunched.loadConversations(using: transport)
        XCTAssertTrue(relaunched.usesRemoteConversations)
        XCTAssertEqual(Set(relaunched.conversations.map(\.id)), [kept, "M1"])

        await relaunched.open(id: kept, using: transport)
        XCTAssertEqual(relaunched.current?.messages.map(\.content), ["Hello", "Kept."])
        XCTAssertTrue(relaunched.storageNote.contains("this device"))

        let asked = transport.streamCallCount
        relaunched.draft = "Again"
        relaunched.send(using: transport)
        try await waitForIdle(relaunched)
        XCTAssertEqual(transport.streamCallCount, asked + 1, "Plain streaming, not the Mac's route")
        XCTAssertTrue(relaunched.usesRemoteConversations)
    }

    // MARK: - A spoken question

    /// The chat screen stays up through a re-pair — the confirmation is a sheet over it —
    /// so push-to-talk has to find the Mac when it asks, not when the screen appeared.
    func testASpokenQuestionGoesToTheMacPairedWhenItIsAsked() async throws {
        let left = try LoopbackServer()
        let paired = try LoopbackServer()
        defer { left.stop(); paired.stop() }
        for mac in [left, paired] {
            mac.events("/chat/stream", """
            event: token
            data: {"text":"Hello."}

            event: finished
            data: {"promptTokens":4,"generatedTokens":2,"tokensPerSecond":10}

            """)
        }
        let app = makeAppModel()
        try app.connect(ServerConfig(host: "127.0.0.1", port: left.port, token: "left-mac-token"))
        let (chat, _) = makeModel()
        let ask = ChatView.askAloud(into: chat, app: app)

        try app.connect(ServerConfig(host: "127.0.0.1", port: paired.port, token: "paired-mac-token"))
        chat.macChanged()
        ask("Which Mac is this")
        try await waitForIdle(chat)

        XCTAssertEqual(chat.current?.messages.last?.content, "Hello.")
        XCTAssertEqual(paired.requestCount("/chat/stream"), 1)
        XCTAssertEqual(
            paired.request(to: "/chat/stream")?.headers["authorization"], "Bearer paired-mac-token"
        )
        XCTAssertTrue(left.requests.isEmpty, "Neither the question nor its token goes to the Mac left behind")
    }

    private func makeAppModel() -> AppModel {
        let suite = "buddy.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.\(UUID().uuidString)")
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suite)
            tokens.delete()
        }
        return AppModel(defaults: defaults, tokens: tokens)
    }

    // MARK: - What a device may send

    func testTooManyPicturesIsRefusedBeforeSending() {
        let attachments = (0..<9).map { _ in ChatAttachment(jpeg: Data([0xFF, 0xD8, 0xFF])) }
        let problem = ChatModel.attachmentProblem(for: attachments, message: "look")
        XCTAssertNotNil(problem)
        XCTAssertTrue(problem?.contains("8") == true)
    }

    func testAPictureOverTheMacsLimitIsRefusedBeforeSending() {
        let big = ChatAttachment(jpeg: Data(repeating: 0, count: SendLimits.maximumImageBytes + 1))
        XCTAssertNotNil(ChatModel.attachmentProblem(for: [big], message: ""))
    }

    func testABodyOverFourMegabytesIsRefusedBeforeSending() {
        // Three pictures just under the per-image limit are over the body limit once
        // base64 has added its third.
        let attachments = (0..<3).map { _ in
            ChatAttachment(jpeg: Data(repeating: 0, count: 1_400_000))
        }
        let problem = ChatModel.attachmentProblem(for: attachments, message: "")
        XCTAssertNotNil(problem)
        XCTAssertTrue(problem?.contains("4 MB") == true)
    }

    func testEightSmallPicturesAreFine() {
        let attachments = (0..<8).map { _ in
            ChatAttachment(jpeg: Data(repeating: 0, count: 100_000))
        }
        XCTAssertNil(ChatModel.attachmentProblem(for: attachments, message: "look"))
    }

    @MainActor
    func testTheNinthPictureIsNotAdded() {
        let (model, _) = makeModel()
        for _ in 0..<9 {
            model.attach(ChatAttachment(jpeg: Data([0xFF, 0xD8, 0xFF])))
        }
        XCTAssertEqual(model.attachments.count, SendLimits.maximumAttachments)
        XCTAssertNotNil(model.error)
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

    /// Right after the phone unlocks Tailscale may not be up yet. A list that could not be
    /// fetched is not an answer: the Mac is asked again, and once it answers the phone uses
    /// its conversations.
    func testAMacOutOfReachAtLaunchIsAskedAgainAboutItsConversations() async {
        let (model, _) = makeModel()
        let transport = StubTransport()
        transport.conversationsResult = .failure(TransportError.unreachable("100.64.0.9"))
        await model.loadConversations(using: transport)
        XCTAssertFalse(model.usesRemoteConversations)

        transport.conversationsResult = .success([
            BuddyAPI.ConversationSummary(id: "M1", title: "On the Mac", updatedAt: Date(), messageCount: 2)
        ])
        await model.askAboutConversationsIfUnanswered(using: transport)
        XCTAssertTrue(model.usesRemoteConversations, "Asked again once the Mac was back")
        XCTAssertEqual(model.conversations.map(\.id), ["M1"])
        XCTAssertEqual(transport.conversationsReads, 2)

        // Answered now: coming back to the front does not ask again.
        await model.askAboutConversationsIfUnanswered(using: transport)
        XCTAssertEqual(transport.conversationsReads, 2)
    }

    func testAMacWithoutConversationsIsAnAnswerAndIsNotAskedAgain() async {
        let (model, _) = makeModel()
        let transport = StubTransport()
        await model.loadConversations(using: transport)
        XCTAssertFalse(model.usesRemoteConversations)
        await model.askAboutConversationsIfUnanswered(using: transport)
        await model.loadConversations(using: transport)
        XCTAssertEqual(transport.conversationsReads, 1, "A 404 is a definite answer")
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
