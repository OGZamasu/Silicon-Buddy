import XCTest
@testable import SiliconBuddy

final class ConversationStoreTests: XCTestCase {

    private func makeStore() -> (ConversationStore, URL) {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString)
        return (ConversationStore(directory: directory), directory)
    }

    func testAnEmptyStoreHasNothingInIt() async {
        let (store, _) = makeStore()
        let all = await store.all()
        XCTAssertTrue(all.isEmpty)
    }

    func testSavingAndReadingBack() async {
        let (store, directory) = makeStore()
        var conversation = Conversation(title: "Kept")
        conversation.messages = [
            ChatMessage(role: .user, content: "hello"),
            ChatMessage(role: .assistant, content: "hi"),
        ]
        await store.save(conversation)

        let reopened = ConversationStore(directory: directory)
        let all = await reopened.all()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all.first?.messages.count, 2)
        XCTAssertEqual(all.first?.title, "Kept")
    }

    func testSavingTwiceUpdatesRatherThanDuplicates() async {
        let (store, _) = makeStore()
        var conversation = Conversation(title: "One")
        await store.save(conversation)
        conversation.messages.append(ChatMessage(role: .user, content: "added"))
        await store.save(conversation)
        let all = await store.all()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all.first?.messages.count, 1)
    }

    func testTheFirstMessageBecomesTheTitle() async {
        let (store, _) = makeStore()
        var conversation = Conversation()
        conversation.messages = [ChatMessage(role: .user, content: "What is a tensor?")]
        let saved = await store.save(conversation)
        XCTAssertEqual(saved.title, "What is a tensor?")
    }

    func testALongFirstMessageIsTruncatedIntoATitle() {
        var conversation = Conversation()
        conversation.messages = [ChatMessage(role: .user, content: String(repeating: "a", count: 100))]
        let titled = conversation.titledFromFirstMessage()
        XCTAssertEqual(titled.title.count, 49)
        XCTAssertTrue(titled.title.hasSuffix("…"))
    }

    func testAnExplicitTitleIsKept() {
        var conversation = Conversation(title: "Mine")
        conversation.messages = [ChatMessage(role: .user, content: "anything")]
        XCTAssertEqual(conversation.titledFromFirstMessage().title, "Mine")
    }

    func testDeleting() async {
        let (store, _) = makeStore()
        let conversation = Conversation(title: "Temporary")
        await store.save(conversation)
        await store.delete(id: conversation.id)
        let all = await store.all()
        XCTAssertTrue(all.isEmpty)
    }

    func testTheSummaryCountsOnlyRealMessages() {
        var conversation = Conversation(title: "t")
        conversation.messages = [
            ChatMessage(role: .system, content: "you are helpful"),
            ChatMessage(role: .user, content: "hi"),
            ChatMessage(role: .assistant, content: "hello"),
        ]
        XCTAssertEqual(conversation.summary.messageCount, 2)
    }

    func testMessagesSurviveEncodingWithTheirImagesAndMetrics() throws {
        let message = ChatMessage(
            role: .assistant, content: "A cat.", reasoning: "It has whiskers.",
            images: ["data:image/jpeg;base64,AAA"],
            metrics: BuddyAPI.ChatMetrics(promptTokens: 4, generatedTokens: 2, tokensPerSecond: 12.5)
        )
        let data = try JSONEncoder.buddy.encode(message)
        var decoded = try JSONDecoder.buddy.decode(ChatMessage.self, from: data)
        // ISO-8601 on the wire keeps whole seconds — the spelling the Mac uses — so a
        // round trip loses the fraction and nothing else.
        XCTAssertEqual(
            decoded.createdAt.timeIntervalSince1970,
            message.createdAt.timeIntervalSince1970,
            accuracy: 1.0
        )
        decoded.createdAt = message.createdAt
        XCTAssertEqual(decoded, message)
    }

    func testNewestConversationsComeFirst() async {
        let (store, _) = makeStore()
        await store.save(Conversation(title: "older"))
        try? await Task.sleep(for: .milliseconds(20))
        await store.save(Conversation(title: "newer"))
        let all = await store.all()
        XCTAssertEqual(all.first?.title, "newer")
    }
}
