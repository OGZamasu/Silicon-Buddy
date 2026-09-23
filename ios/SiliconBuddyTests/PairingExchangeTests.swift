import XCTest
@testable import SiliconBuddy

/// One pairing code at a time, owned by `AppModel` and not by the sheet that spent it.
///
/// Each pair button used to start a `Task` of its own. Close the sheet while code X was
/// with its Mac, pair Y, and X's answer — landing after Y's — silently replaced Y: the last
/// exchange to *land* won, not the last one chosen, and nobody was asked. And a refusal that
/// landed after its sheet had closed was said nowhere.
@MainActor
final class PairingExchangeTests: XCTestCase {

    private var server: LoopbackServer!
    private var mac: StubTransport!

    /// A link or a QR, and a second one for another Mac.
    private let x = PairingInvite(host: "127.0.0.1", port: 8788, code: "418203")
    private let y = PairingInvite(host: "127.0.0.1", port: 8789, code: "135790")

    override func setUp() async throws {
        try await super.setUp()
        // What a paired app asks next — `/health`, `/status` — goes here, and gets a 404:
        // a Mac of the test's own, never whatever else listens on this machine.
        server = try LoopbackServer()
        mac = StubTransport()
    }

    override func tearDown() async throws {
        server.stop()
        server = nil
        mac = nil
        try await super.tearDown()
    }

    func testTheMacsAnswerIsKeptWhenTheScreenThatAskedHasGone() async throws {
        let app = makeAppModel()
        let gate = Gate()
        mac.pairHold = { await gate.wait() }
        mac.pairResult = .success(answer("token-x", "Mac X"))

        app.pairingScreenOpened()
        XCTAssertTrue(app.startPairing(x))
        XCTAssertEqual(app.pairing.spending, x)
        app.pairingScreenClosed()
        XCTAssertEqual(app.pairing.spending, x, "closing the sheet does not end the exchange")

        await gate.open()
        await app.pairing.settled()
        XCTAssertEqual(app.config?.token, "token-x")
        XCTAssertEqual(app.config?.macName, "Mac X")
        XCTAssertEqual(app.pairing.state, .idle)
    }

    /// The critic's case: X is still with its Mac when Y is asked for.
    func testALaterCodeWaitsAndStillNeedsItsOwnReplaceConfirmation() async throws {
        let app = makeAppModel()
        let gate = Gate()
        mac.pairHold = { await gate.wait() }
        mac.pairResult = .success(answer("token-x", "Mac X"))

        XCTAssertTrue(app.startPairing(x))
        await waitUntil { self.mac.pairedCodes.count == 1 }
        // Y, from a link, while X is out. Nothing is dialled for it.
        app.pendingInvite = y
        XCTAssertEqual(app.pairing.phase(of: y), .waiting(for: x))
        XCTAssertFalse(app.startPairing(y), "a second code does not start while one is out")
        XCTAssertEqual(mac.pairedCodes, [x.code])

        await gate.open()
        await app.pairing.settled()
        XCTAssertEqual(app.config?.token, "token-x", "the code spent first lands first")
        XCTAssertEqual(app.pendingInvite, y, "X's landing does not answer Y")
        XCTAssertEqual(app.pairing.phase(of: y), .ready)
        // So Y's confirmation now says "This replaces Mac X" and asks "Replace this Mac…"
        // before anything of Y's is spent: X is not replaced without a yes.
        XCTAssertTrue(app.isPaired)
        XCTAssertEqual(app.macDisplayName, "Mac X")

        mac.pairResult = .success(answer("token-y", "Mac Y"))
        XCTAssertTrue(app.startPairing(y))
        await app.pairing.settled()
        XCTAssertEqual(mac.pairedCodes, [x.code, y.code])
        XCTAssertEqual(app.config?.token, "token-y")
        XCTAssertNil(app.pendingInvite, "Y was answered by its own pairing")
    }

    func testARefusalThatLandsAfterTheSheetClosedIsSaidByTheApp() async throws {
        let app = makeAppModel()
        let gate = Gate()
        mac.pairHold = { await gate.wait() }
        mac.pairResult = .failure(TransportError.forbidden("That pairing code is not the one on screen."))

        app.pairingScreenOpened()
        XCTAssertTrue(app.startPairing(x))
        app.pairingScreenClosed()
        XCTAssertNil(app.unseenPairingFailure)

        await gate.open()
        await app.pairing.settled()
        let failure = try XCTUnwrap(app.unseenPairingFailure, "nothing was left to say it, and nothing did")
        XCTAssertEqual(failure.invite, x)
        XCTAssertEqual(failure.message, "That pairing code is not the one on screen.")
        XCTAssertFalse(failure.macTooOld)
        XCTAssertFalse(app.isPaired)

        // Held back while something else is up that says it — and there until it is seen.
        app.pendingInvite = y
        XCTAssertNil(app.unseenPairingFailure)
        XCTAssertEqual(
            app.pairing.phase(of: y), .otherFailed(x, "That pairing code is not the one on screen.")
        )
        app.pendingInvite = nil
        XCTAssertEqual(app.unseenPairingFailure, failure)
        app.pairing.acknowledge()
        XCTAssertNil(app.unseenPairingFailure)
        XCTAssertEqual(app.pairing.state, .idle)
    }

    func testARefusalASheetIsShowingIsSeenWhenItCloses() async throws {
        let app = makeAppModel()
        mac.pairResult = .failure(TransportError.forbidden("That pairing code is not the one on screen."))

        app.pairingScreenOpened()
        XCTAssertTrue(app.startPairing(x))
        await app.pairing.settled()
        XCTAssertNotNil(app.pairing.failure, "the open sheet shows it")
        XCTAssertNil(app.unseenPairingFailure, "so the app does not say it twice")
        XCTAssertEqual(app.pairing.phase(of: x), .failed("That pairing code is not the one on screen.", macTooOld: false))

        app.pairingScreenClosed()
        XCTAssertNil(app.pairing.failure)
        XCTAssertNil(app.unseenPairingFailure)
    }

    func testARefusedCodeKeepsTheMacAlreadyPaired() async throws {
        let app = makeAppModel()
        mac.pairResult = .success(answer("token-x", "Mac X"))
        app.startPairing(x)
        await app.pairing.settled()
        let generation = app.connectionGeneration

        mac.pairResult = .failure(TransportError.forbidden("That pairing code is not the one on screen."))
        app.startPairing(y)
        await app.pairing.settled()
        XCTAssertEqual(app.config?.token, "token-x")
        XCTAssertEqual(app.connectionGeneration, generation)
    }

    func testAMacTooOldForCodesIsToldApart() async throws {
        let app = makeAppModel()
        mac.pairResult = .failure(TransportError.routeUnavailable("/buddy/pair"))
        app.pendingInvite = x
        app.startPairing(x)
        await app.pairing.settled()
        XCTAssertEqual(
            app.pairing.phase(of: x), .failed(PairingExchange.macTooOldForCodes, macTooOld: true)
        )
        XCTAssertEqual(app.pendingInvite, x, "still asked about, so the sheet can say why")
    }

    func testNothingIsDialledForAnInviteOffTheTailnet() async throws {
        let app = makeAppModel()
        mac.pairResult = .success(answer("token-x", "Mac X"))
        app.startPairing(PairingInvite(host: "192.168.1.10", port: 8788, code: "418203"))
        await app.pairing.settled()
        XCTAssertTrue(mac.pairedCodes.isEmpty)
        XCTAssertEqual(app.pairing.failure?.message, TailnetHost.explanation)
        XCTAssertFalse(app.isPaired)
    }

    // MARK: - Helpers

    private func answer(_ token: String, _ name: String) -> BuddyAPI.PairResponse {
        BuddyAPI.PairResponse(
            deviceID: "D-\(token)", token: token, macName: name, port: server.port, scope: "full"
        )
    }

    private func makeAppModel() -> AppModel {
        let suite = "buddy.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.\(UUID().uuidString)")
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suite)
            tokens.delete()
        }
        let mac = mac!
        return AppModel(defaults: defaults, tokens: tokens, pairingClient: { _ in mac })
    }

    private func waitUntil(_ condition: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while !condition(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }
}

/// A Mac that has the code and has not answered yet, until `open()`.
private actor Gate {
    private var isOpen = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func open() {
        isOpen = true
        waiting.forEach { $0.resume() }
        waiting = []
    }
}
