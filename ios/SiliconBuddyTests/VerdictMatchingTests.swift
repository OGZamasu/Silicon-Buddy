import XCTest
@testable import SiliconBuddy

/// Which reply an answer check is allowed to decorate.
@MainActor
final class VerdictMatchingTests: XCTestCase {

    private func stub() -> StubTransport {
        let transport = StubTransport()
        transport.conversationsResult = .success([
            BuddyAPI.ConversationSummary(
                id: "C1", title: "Three days in Lisbon", updatedAt: Date(), messageCount: 4
            )
        ])
        transport.conversationDetail = BuddyAPI.ConversationDetail(
            id: "C1", title: "Three days in Lisbon", updatedAt: Date(),
            messages: [
                BuddyAPI.StoredMessage(
                    id: "m1", role: "user", content: "first question", createdAt: Date()
                ),
                BuddyAPI.StoredMessage(
                    id: "m2", role: "assistant", content: "first answer", createdAt: Date()
                ),
                BuddyAPI.StoredMessage(
                    id: "m3", role: "user", content: "second question", createdAt: Date()
                ),
                BuddyAPI.StoredMessage(
                    id: "m4", role: "assistant", content: "second answer", createdAt: Date()
                ),
            ]
        )
        return transport
    }

    private func openedModel() async -> ChatModel {
        let model = ChatModel()
        let transport = stub()
        await model.loadConversations(using: transport)
        await model.open(id: "C1", using: transport)
        return model
    }

    private func verdict(
        messageID: String? = nil, conversationID: String? = nil
    ) -> BuddyAPI.Verdict {
        BuddyAPI.Verdict(
            conversationID: conversationID, messageID: messageID, verdict: "annotate"
        )
    }

    func testTheMacsMessageIDsSurviveLoadingATranscript() async {
        let model = await openedModel()
        XCTAssertEqual(model.current?.messages.map(\.id), ["m1", "m2", "m3", "m4"])
    }

    func testANamedMessageIDLandsOnExactlyThatMessage() async {
        let model = await openedModel()
        model.apply(verdict: verdict(messageID: "m2"))
        XCTAssertNotNil(model.current?.messages[1].verdict)
        XCTAssertNil(model.current?.messages[3].verdict, "and on nothing else")
    }

    /// The one that matters. A check naming a message this screen does not hold used to
    /// be stamped on the newest reply instead, which puts the Mac's words under an
    /// answer it never read.
    func testANamedMessageIDThatMatchesNothingIsDroppedNotMoved() async {
        let model = await openedModel()
        model.apply(verdict: verdict(messageID: "somewhere-else"))
        XCTAssertTrue(
            model.current?.messages.allSatisfy { $0.verdict == nil } ?? false,
            "nothing was decorated"
        )
    }

    func testAnUnnamedCheckFallsBackToTheNewestFinishedReply() async {
        let model = await openedModel()
        model.apply(verdict: verdict())
        XCTAssertNotNil(model.current?.messages[3].verdict)
        XCTAssertNil(model.current?.messages[1].verdict)
    }

    func testAnEmptyMessageIDCountsAsUnnamed() async {
        let model = await openedModel()
        model.apply(verdict: verdict(messageID: ""))
        XCTAssertNotNil(model.current?.messages[3].verdict)
    }

    func testACheckForAnotherConversationIsIgnored() async {
        let model = await openedModel()
        model.apply(verdict: verdict(messageID: "m2", conversationID: "C9"))
        XCTAssertTrue(model.current?.messages.allSatisfy { $0.verdict == nil } ?? false)
    }

    /// A check the Mac already stored comes back with the transcript rather than as an
    /// event, and has to be shown the same way.
    func testAStoredCheckArrivesWithTheTranscript() async {
        let model = ChatModel()
        let transport = stub()
        transport.conversationDetail = BuddyAPI.ConversationDetail(
            id: "C1", title: "t", updatedAt: Date(),
            messages: [
                BuddyAPI.StoredMessage(
                    id: "m1", role: "assistant", content: "an answer", createdAt: Date(),
                    verification: BuddyAPI.Verdict(verdict: "escalate", reasons: ["a reason"])
                )
            ]
        )
        await model.loadConversations(using: transport)
        await model.open(id: "C1", using: transport)
        XCTAssertEqual(model.current?.messages.first?.verdict?.verdict, "escalate")
    }
}
