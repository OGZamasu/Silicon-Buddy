import XCTest
@testable import SiliconBuddy

/// A scanned QR, or a link any app can open, names the machine this device is about to
/// hand a bearer token to. These are the tests that keep that from being anyone's
/// choice but the owner's.
final class PairingSecurityTests: XCTestCase {

    // MARK: - Which hosts exist at all

    func testTheTailnetAndTheLoopbacksAreAllowed() {
        for host in [
            "127.0.0.1", "127.1.2.3", "localhost", "::1",
            "10.0.2.2",                     // the Android emulator's name for its host
            "100.64.0.1", "100.100.100.100", "100.127.255.254",
            "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:625a:1",
            "[fd7a:115c:a1e0::1]",
        ] {
            XCTAssertTrue(TailnetHost.isAllowed(host), "\(host) should be reachable")
        }
    }

    func testEverythingElseIsRefused() {
        for host in [
            "evil.example.com", "example.com", "192.168.1.10", "10.0.0.5", "172.16.0.1",
            "8.8.8.8", "100.63.255.255", "100.128.0.1", "0.0.0.0", "255.255.255.255",
            "fd7a:115c:a1e1::1", "2001:4860:4860::8888", "",
            // The oldest trick there is: a name that starts with an address.
            "100.64.0.1.evil.example.com",
            // And the other one: a userinfo prefix that reads like a host.
            "100.64.0.1@evil.example.com",
        ] {
            XCTAssertFalse(TailnetHost.isAllowed(host), "\(host) must not be dialled")
        }
    }

    func testAnAddressIsParsedNotScannedForDigits() {
        XCTAssertFalse(TailnetHost.isAllowed("100.064.0.1"), "No octal-looking octets")
        XCTAssertFalse(TailnetHost.isAllowed("100.64.0"), "Three parts is not an address")
        XCTAssertFalse(TailnetHost.isAllowed("100.64.0.1.2"))
        XCTAssertFalse(TailnetHost.isAllowed("100.64.0.256"))
        XCTAssertFalse(TailnetHost.isAllowed("fd7a:115c:a1e0::1%en0"), "A zone is an interface")
    }

    // MARK: - What a QR may say

    func testAPairingLinkToAnotherHostIsRefusedBeforeAnythingIsDialled() {
        for host in ["evil.example.com", "192.168.1.10", "8.8.8.8"] {
            let url = "siliconbuddy://pair?host=\(host)&port=8788&code=123456"
            XCTAssertThrowsError(try PairingInvite.parse(url)) { error in
                XCTAssertEqual(
                    error as? PairingInvite.ParseError, .hostNotOnTailnet(host),
                    "\(host) should be refused by the parser, not by the network"
                )
            }
        }
    }

    func testATailnetPairingLinkParses() throws {
        let invite = try PairingInvite.parse(
            "siliconbuddy://pair?host=100.100.100.100&port=8788&code=418203"
        )
        XCTAssertEqual(invite.host, "100.100.100.100")
    }

    func testTheRefusalExplainsItself() {
        let error = PairingInvite.ParseError.hostNotOnTailnet("evil.example.com")
        let text = error.errorDescription ?? ""
        XCTAssertTrue(text.contains("tailnet"))
        XCTAssertTrue(text.contains("evil.example.com"))
    }

    // MARK: - What the app does with one

    @MainActor
    func testPairingRefusesAHostThatIsNotOnTheTailnet() async {
        let app = makeAppModel()
        let invite = PairingInvite(host: "evil.example.com", port: 8788, code: "123456")
        XCTAssertTrue(app.startPairing(invite))
        await app.pairing.settled()
        XCTAssertEqual(
            app.pairing.state,
            .failed(invite, message: TailnetHost.explanation, macTooOld: false),
            "Pairing should have refused that host"
        )
        XCTAssertFalse(app.isPaired)
    }

    @MainActor
    func testConnectRefusesAHostThatIsNotOnTheTailnet() {
        let app = makeAppModel()
        XCTAssertThrowsError(
            try app.connect(ServerConfig(host: "example.com", port: 8788, token: "t"))
        )
        XCTAssertFalse(app.isPaired)
    }

    @MainActor
    func testAnArrivingLinkIsHeldForConfirmationRatherThanActedOn() {
        let app = makeAppModel()
        let invite = PairingInvite(host: "100.64.0.1", port: 8788, code: "123456")
        // What `onOpenURL` does: it puts the invite somewhere the sheet can find it.
        app.pendingInvite = invite
        XCTAssertFalse(app.isPaired, "Nothing is paired until the person says so")
        XCTAssertEqual(app.pendingInvite, invite)
    }

    @MainActor
    func testConnectingToADifferentMacClearsTheOldOne() throws {
        let app = makeAppModel()
        try app.connect(
            ServerConfig(host: "100.64.0.1", port: 8788, token: "one", macName: "First")
        )
        let firstGeneration = app.connectionGeneration
        XCTAssertEqual(app.macDisplayName, "First")

        try app.connect(
            ServerConfig(host: "100.64.0.2", port: 8788, token: "two", macName: "Second")
        )
        XCTAssertEqual(app.macDisplayName, "Second")
        XCTAssertNil(app.status, "The first Mac's status must not survive")
        XCTAssertGreaterThan(
            app.connectionGeneration, firstGeneration,
            "Screens watch this to throw away the last Mac's readings"
        )
    }

    @MainActor
    func testForgettingClearsEverything() throws {
        let app = makeAppModel()
        try app.connect(ServerConfig(host: "100.64.0.1", port: 8788, token: "one"))
        let generation = app.connectionGeneration
        app.forget()
        XCTAssertFalse(app.isPaired)
        XCTAssertNil(app.config)
        XCTAssertGreaterThan(app.connectionGeneration, generation)
    }

    // MARK: - Scope

    @MainActor
    func testAChatScopeDeviceIsNotOfferedControl() throws {
        let app = makeAppModel()
        try app.connect(
            ServerConfig(host: "100.64.0.1", port: 8788, token: "t", scope: .chat)
        )
        XCTAssertFalse(app.canControl)
        XCTAssertTrue(app.scope.explanation.contains("chat only"))
    }

    @MainActor
    func testAFullScopeDeviceIsOfferedControl() throws {
        let app = makeAppModel()
        try app.connect(ServerConfig(host: "100.64.0.1", port: 8788, token: "t", scope: .full))
        XCTAssertTrue(app.canControl)
    }

    @MainActor
    func testTheScopeSurvivesARestart() throws {
        let suite = "buddy.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.\(UUID().uuidString)")
        defer {
            defaults.removePersistentDomain(forName: suite)
            tokens.delete()
        }
        let app = AppModel(defaults: defaults, tokens: tokens)
        try app.connect(
            ServerConfig(host: "100.64.0.1", port: 8788, token: "t", scope: .chat)
        )
        let reopened = AppModel(defaults: defaults, tokens: tokens)
        XCTAssertEqual(reopened.scope, .chat)
        XCTAssertFalse(reopened.canControl)
    }

    // MARK: - Helpers

    @MainActor
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
}
