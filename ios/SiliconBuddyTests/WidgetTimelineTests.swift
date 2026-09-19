import XCTest
@testable import SiliconBuddy

/// The widget's timeline, against a real socket.
///
/// A widget is the one part of this app that runs when nothing else does, so the thing
/// worth testing is what it draws when the Mac does not answer — and that it never
/// throws, because a widget has nowhere to put an error.
final class WidgetTimelineTests: XCTestCase {

    private var server: LoopbackServer!
    private var client: ControlClient!
    private var defaults: UserDefaults!
    private let suite = "dev.siliconoptimizer.buddy.widget-tests"

    override func setUpWithError() throws {
        try super.setUpWithError()
        server = try LoopbackServer()
        client = ControlClient(
            config: ServerConfig(host: "127.0.0.1", port: server.port, token: "device-token")
        )
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        server.stop()
        server = nil
        client = nil
        defaults.removePersistentDomain(forName: suite)
        defaults = nil
        super.tearDown()
    }

    private let status = #"""
    {"state":"Ready","loadedModelID":"qwen3-4b-mlx@MLX-4bit","loadedModelName":"Qwen3 4B (MLX)","contextLength":65536,"expertStreaming":false,"lastGenerationTokensPerSecond":68.4}
    """#

    // MARK: - Drawing what the Mac says

    func testAnEntryNamesTheModelTheMacHasLoaded() async {
        server.reply("/status", 200, status)
        let entry = await WidgetTimeline.entry(
            using: client, stored: nil, quickPrompt: QuickPrompt.default
        )
        XCTAssertTrue(entry.isPaired)
        XCTAssertNil(entry.problem)
        XCTAssertEqual(entry.headline, "Qwen3 4B (MLX)")
        XCTAssertEqual(entry.snapshot?.loadedModelID, "qwen3-4b-mlx@MLX-4bit")
    }

    func testTheLastAnswerSurvivesARefresh() async {
        server.reply("/status", 200, status)
        let stored = BuddySnapshot(lastQuestion: "Hello?", lastAnswer: "Hello yourself.")
        let entry = await WidgetTimeline.entry(
            using: client, stored: stored, quickPrompt: QuickPrompt.default
        )
        XCTAssertEqual(entry.body(limit: 90), "Hello yourself.")
        XCTAssertEqual(entry.headline, "Qwen3 4B (MLX)")
    }

    // MARK: - Drawing when it does not

    /// The case a widget spends most of its life in: the Mac is asleep, or the phone is
    /// off the tailnet. The last snapshot is still the best thing anybody knows.
    func testAMacThatDoesNotAnswerKeepsTheLastSnapshotAndSaysWhy() async {
        server.reply("/status", 503, #"{"error":"busy"}"#)
        let stored = BuddySnapshot(loadedModelName: "Gemma 3 12B", lastAnswer: "Earlier.")
        let entry = await WidgetTimeline.entry(
            using: client, stored: stored, quickPrompt: QuickPrompt.default
        )
        XCTAssertTrue(entry.isPaired)
        XCTAssertEqual(entry.headline, "Gemma 3 12B")
        XCTAssertEqual(entry.problem, "Your Mac isn't reachable right now.")
    }

    func testARevokedDeviceIsToldItIsNoLongerPaired() async {
        server.reply("/status", 401, #"{"error":"Invalid or missing control token."}"#)
        let entry = await WidgetTimeline.entry(
            using: client, stored: nil, quickPrompt: QuickPrompt.default
        )
        XCTAssertEqual(entry.problem, "This device is no longer paired with your Mac.")
    }

    func testNoMacAtAllAsksToPairRatherThanShowingAnError() async {
        let entry = await WidgetTimeline.entry(
            using: nil, stored: nil, quickPrompt: QuickPrompt.default
        )
        XCTAssertFalse(entry.isPaired)
        XCTAssertEqual(entry.headline, "Not paired")
        XCTAssertEqual(entry.problem, "Open Silicon Buddy to pair with your Mac.")
    }

    // MARK: - How often it comes back

    func testItComesBackSoonerWhenTheMacIsAnswering() {
        let now = Date()
        let good = WidgetTimeline.nextRefresh(after: now, succeeded: true)
        let bad = WidgetTimeline.nextRefresh(after: now, succeeded: false)
        XCTAssertEqual(good.timeIntervalSince(now), WidgetTimeline.refreshInterval, accuracy: 1)
        XCTAssertGreaterThan(bad, good)
    }

    // MARK: - The button

    func testTheQuickPromptAsksOnceAndKeepsTheAnswer() async {
        server.reply("/status", 200, status)
        server.reply(
            "/chat", 200,
            #"{"content":"Take a walk.","reasoning":null,"promptTokens":9,"generatedTokens":4,"tokensPerSecond":30.0}"#
        )
        let result = await WidgetTimeline.ask(
            "What should I do next?", using: client, defaults: defaults
        )
        XCTAssertEqual(result, .answer("Take a walk."))

        // The answer is left where the app and the Lock Screen read it, so "the last
        // answer" means the last one wherever it was asked.
        let snapshot = SnapshotStore.read(from: defaults)
        XCTAssertEqual(snapshot?.lastAnswer, "Take a walk.")
        XCTAssertEqual(snapshot?.lastQuestion, "What should I do next?")

        // One message, no history: a widget has no transcript behind it.
        let sent = server.requests.first { $0.path == "/chat" }
        let body = try? JSONDecoder.buddy.decode(
            ControlAPI.ChatRequest.self, from: sent?.body ?? Data()
        )
        XCTAssertEqual(body?.messages.count, 1)
        XCTAssertEqual(body?.messages.first?.content, "What should I do next?")
    }

    func testAButtonPressAgainstAnUnreachableMacShowsASentenceRatherThanNothing() async {
        server.reply("/chat", 500, #"{"error":"boom"}"#)
        let result = await WidgetTimeline.ask("Anything?", using: client, defaults: defaults)
        guard case .problem(let sentence) = result else {
            return XCTFail("expected a problem, got \(result)")
        }
        XCTAssertFalse(sentence.isEmpty)
        XCTAssertNil(SnapshotStore.read(from: defaults)?.lastAnswer)
    }

    func testAButtonPressWithNoMacSaysSo() async {
        let result = await WidgetTimeline.ask("Anything?", using: nil, defaults: defaults)
        XCTAssertEqual(result, .problem("Not paired with a Mac."))
    }

    // MARK: - Trimming for a small surface

    func testAnAnswerIsCutOnAWordBoundary() {
        let trimmed = BuddySnapshot.trim("the quick brown fox jumps", to: 12)
        XCTAssertEqual(trimmed, "the quick…")
    }

    func testAShortAnswerIsLeftAlone() {
        XCTAssertEqual(BuddySnapshot.trim("short", to: 40), "short")
    }

    func testNewlinesAreFlattenedSoAWidgetDoesNotShowOneWordPerLine() {
        XCTAssertEqual(BuddySnapshot.trim("a\nb\nc", to: 40), "a b c")
    }

    // MARK: - What the snapshot keeps

    func testTheSnapshotRemembersWhatTheMacIsRunningAndWhatItLastSaid() {
        SnapshotStore.note(
            status: ControlAPI.Status(
                state: "Ready", loadedModelID: "x@Q4", loadedModelName: "X"
            ),
            macName: "Studio", to: defaults
        )
        SnapshotStore.note(question: "Q", answer: "A", to: defaults)
        let snapshot = SnapshotStore.read(from: defaults)
        XCTAssertEqual(snapshot?.loadedModelName, "X")
        XCTAssertEqual(snapshot?.macName, "Studio")
        XCTAssertEqual(snapshot?.lastAnswer, "A")
    }

    /// Re-pairing must not leave the last Mac's model on the Home Screen.
    func testClearingTheSnapshotLeavesNothingBehind() {
        SnapshotStore.note(question: "Q", answer: "A", to: defaults)
        SnapshotStore.clear(from: defaults)
        XCTAssertNil(SnapshotStore.read(from: defaults))
    }
}
