import XCTest
@testable import SiliconBuddy

/// A scanned QR, or a link any app can open, names the machine this device is about to
/// hand a bearer token to. These are the tests that keep that from being anyone's
/// choice but the owner's.
final class PairingSecurityTests: XCTestCase {

    // MARK: - Which hosts exist at all

    /// A Mac on this machine: loopback, and the Android emulator's name for its host.
    private let localHosts = [
        "127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]",
        "10.0.2.2",                     // the Android emulator's name for its host
    ]

    func testTheTailnetIsAllowedInEveryBuild() {
        for host in [
            "100.64.0.1", "100.100.100.100", "100.127.255.254",
            "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:625a:1",
            "[fd7a:115c:a1e0::1]",
        ] {
            XCTAssertTrue(TailnetHost.isAllowed(host, local: false), "\(host) should be reachable")
            XCTAssertTrue(TailnetHost.isAllowed(host, local: true), "\(host) should be reachable")
            XCTAssertFalse(TailnetHost.isLocal(host))
        }
    }

    /// The build the owner installs dials the tailnet and nothing else. On a phone,
    /// loopback is whichever other app is listening there, and 10.0.2.2 is an ordinary
    /// address on whatever Wi-Fi it has joined: a pairing link naming either would hand
    /// this device's token to a machine that is not the owner's Mac.
    func testTheBuildTheOwnerInstallsDialsTheTailnetAndNothingElse() {
        for host in localHosts {
            XCTAssertTrue(TailnetHost.isLocal(host), "\(host) is this machine")
            XCTAssertFalse(TailnetHost.isAllowed(host, local: false), "\(host) must not be dialled")
        }
        XCTAssertFalse(
            TailnetHost.explanation(local: false).contains("127.0.0.1"),
            "A build that will not dial the Simulator's Mac does not suggest it"
        )
    }

    /// The Simulator shares the Mac's loopback, and this suite pairs with Macs there: the
    /// DEBUG build it runs is the one that dials this machine.
    func testTheDebugBuildDialsAMacOnThisMachine() {
        XCTAssertTrue(TailnetHost.allowsLocal, "The tests run the DEBUG build")
        for host in localHosts {
            XCTAssertTrue(TailnetHost.isAllowed(host), "\(host) should be reachable here")
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

    // MARK: - The Mac's own control token

    /// The Developer form sends the Mac's control token, which the Mac takes only on its
    /// loopback listener. Anywhere else — a tailnet address, the emulator's 10.0.2.2 — it
    /// is refused at best, and at worst answered by a machine that is not the Mac.
    func testTheDeveloperFormSendsTheControlTokenOnlyToThisMachine() {
        for host in ["100.64.0.9", "fd7a:115c:a1e0::9", "10.0.2.2", "192.168.1.10", "evil.example.com"] {
            XCTAssertNotNil(PairingView.developerHostProblem(host), "\(host) must not get the token")
        }
        for host in ["127.0.0.1", "localhost", "::1", " 127.0.0.1 "] {
            XCTAssertNil(PairingView.developerHostProblem(host), "\(host) is the Mac itself")
        }
    }

    func testABuildThatDialsNoLocalMacSendsTheControlTokenNowhere() {
        XCTAssertNotNil(PairingView.developerHostProblem("127.0.0.1", local: false))
        XCTAssertEqual(PairingView.Mode.offered(local: false), [.scan, .code])
        XCTAssertEqual(PairingView.Mode.offered(local: true), [.scan, .code, .developer])
    }

    /// The Simulator's DEBUG build opens on Developer with loopback filled in. A Release
    /// build — in the Simulator too — has no Developer form, so it never opens on one.
    func testTheSheetOpensOnlyOnAFormThisBuildOffers() {
        for simulator in [true, false] {
            for local in [true, false] {
                let opening = PairingView.opening(simulator: simulator, local: local)
                XCTAssertTrue(
                    PairingView.Mode.offered(local: local).contains(opening.mode),
                    "simulator \(simulator), local \(local) opens on \(opening.mode)"
                )
            }
        }
        let simulatorDebug = PairingView.opening(simulator: true, local: true)
        XCTAssertEqual(simulatorDebug.mode, .developer)
        XCTAssertEqual(simulatorDebug.developerHost, "127.0.0.1")
        let simulatorRelease = PairingView.opening(simulator: true, local: false)
        XCTAssertEqual(simulatorRelease.mode, .scan)
        XCTAssertEqual(simulatorRelease.developerHost, "")
        XCTAssertEqual(PairingView.opening(simulator: false, local: true).mode, .scan)
    }

    /// The note under Enter code's address names Developer, so it is shown only where
    /// Developer is.
    func testACodeTypedWithALocalAddressIsPointedAtTheTailnetOnlyWhereDeveloperIsOffered() {
        for address in ["127.0.0.1", "localhost", "::1", "10.0.2.2", "127.0.0.1:8788"] {
            XCTAssertNotNil(PairingView.codeAddressHint(address, local: true), address)
            XCTAssertNil(PairingView.codeAddressHint(address, local: false), address)
        }
        for address in ["100.64.0.9", "100.64.0.9:8788", "fd7a:115c:a1e0::9", "", "evil.example.com"] {
            XCTAssertNil(PairingView.codeAddressHint(address, local: true), address)
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
