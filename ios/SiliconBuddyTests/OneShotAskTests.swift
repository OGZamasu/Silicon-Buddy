import XCTest
@testable import SiliconBuddy

/// The share sheet's, a widget's and Shortcuts' one question: asked once, on the best
/// route the Mac has.
final class OneShotAskTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        suite = "buddy.tests.oneshot.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    /// A reasoning model can spend the whole budget thinking. That is the answer to this
    /// question, not a route that is missing — asking the next route generates it again.
    func testAReplyThatOnlyThoughtIsGeneratedOnceNotThreeTimes() async {
        let transport = StubTransport()
        transport.createdConversation = BuddyAPI.ConversationSummary(
            id: "C1", title: "Shared", updatedAt: Date(), messageCount: 0
        )
        transport.streamEvents = [
            .reasoning("Let me think about every possibility…"),
            .finished(BuddyAPI.ChatMetrics(promptTokens: 20, generatedTokens: 1024, tokensPerSecond: 30)),
        ]
        transport.chatResult = .success(
            ControlAPI.ChatResponse(
                content: "", reasoning: "Still thinking…", promptTokens: 20,
                generatedTokens: 1024, tokensPerSecond: 30
            )
        )
        do {
            _ = try await OneShotAsk.send(message: "Why is the sky blue?", using: transport, defaults: defaults)
            XCTFail("An answer with nothing in it is not an answer")
        } catch {
            XCTAssertEqual(error as? OneShotAsk.Failure, .onlyThought)
        }
        XCTAssertEqual(transport.streamCallCount, 1, "Asked on the conversation route only")
        XCTAssertEqual(transport.chatCallCount, 0, "And never again on /chat")
    }

    /// Siri or Shortcuts giving up on the question, or the share sheet closing, cancels the
    /// ask. The stream it was reading then ends with nothing — which is not a route with
    /// nothing to say, and the next two routes must not be asked the same question.
    func testACancelledAskTriesNoOtherRoute() async throws {
        let transport = StubTransport()
        transport.createdConversation = BuddyAPI.ConversationSummary(
            id: "C1", title: "Shared", updatedAt: Date(), messageCount: 0
        )
        transport.streamHangs = true
        let suite = self.suite!
        let asking = Task {
            try await OneShotAsk.send(
                message: "Why is the sky blue?", using: transport,
                defaults: UserDefaults(suiteName: suite)!
            )
        }
        for _ in 0..<200 where transport.streamCallCount == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }
        asking.cancel()
        do {
            _ = try await asking.value
            XCTFail("A cancelled ask has no answer")
        } catch {
            XCTAssertTrue(error is CancellationError, "Got \(error)")
        }
        XCTAssertEqual(transport.streamCallCount, 1, "Not /chat/stream as well")
        XCTAssertEqual(transport.chatCallCount, 0, "Nor /chat")
    }

    func testAMacWithoutConversationsIsAskedOnThePlainStream() async throws {
        let transport = StubTransport()
        transport.streamEvents = [
            .token("Rayleigh scattering."),
            .finished(BuddyAPI.ChatMetrics(promptTokens: 8, generatedTokens: 3, tokensPerSecond: 30)),
        ]
        let outcome = try await OneShotAsk.send(message: "Why?", using: transport, defaults: defaults)
        XCTAssertEqual(outcome.answer, "Rayleigh scattering.")
        XCTAssertFalse(outcome.storedOnMac)
        XCTAssertEqual(transport.streamCallCount, 1)
        XCTAssertEqual(transport.chatCallCount, 0)
    }

    func testAMacWithoutStreamingIsAskedOnPlainChat() async throws {
        let transport = StubTransport()
        transport.chatResult = .success(
            ControlAPI.ChatResponse(
                content: "Blue light scatters most.", reasoning: nil, promptTokens: 8,
                generatedTokens: 5, tokensPerSecond: 30
            )
        )
        let outcome = try await OneShotAsk.send(message: "Why?", using: transport, defaults: defaults)
        XCTAssertEqual(outcome.answer, "Blue light scatters most.")
        XCTAssertEqual(transport.chatCallCount, 1)
    }
}
