import XCTest
@testable import SiliconBuddy

/// Which Silicon Optimizer the device says it is talking to (OGZamasu/silicon-optimizer#81).
///
/// A Mac used to answer `/health` with a literal "0.1.0", so a device connected to 0.5.0
/// build 157 said 0.1.0. A current Mac says `appVersion` and `appBuild`; an older one still
/// answers, and the device has to show something true for both.
final class AppVersionTests: XCTestCase {

    /// `/health` from a Mac with #81: the running bundle's version and build.
    private static let newMac = #"{"status":"ok","version":"0.5.0","appVersion":"0.5.0","appBuild":"157"}"#
    /// `/health` from a Mac before it: one field, whatever the app really was.
    private static let oldMac = #"{"status":"ok","version":"0.1.0"}"#

    private var server: LoopbackServer!

    override func setUpWithError() throws {
        try super.setUpWithError()
        server = try LoopbackServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
        super.tearDown()
    }

    private func health(_ text: String) throws -> ControlAPI.Health {
        try JSONDecoder().decode(ControlAPI.Health.self, from: Data(text.utf8))
    }

    // MARK: - What each Mac says

    func testACurrentMacsVersionAndBuild() throws {
        XCTAssertEqual(try health(Self.newMac).appVersionLabel, "0.5.0 (157)")
    }

    func testAnOlderMacsVersionField() throws {
        XCTAssertEqual(try health(Self.oldMac).appVersionLabel, "0.1.0")
    }

    func testTheAppVersionWinsOverWhateverVersionSays() throws {
        // A Mac is free to mean something else by `version` — a protocol, say. Once it names
        // its app version, that is the one shown.
        let health = try health(#"{"status":"ok","version":"2","appVersion":"0.6.0","appBuild":"170"}"#)
        XCTAssertEqual(health.appVersionLabel, "0.6.0 (170)")
    }

    func testABuildIsShownOnlyBesideANamedAppVersion() throws {
        XCTAssertEqual(
            try health(#"{"status":"ok","version":"0.1.0","appBuild":"157"}"#).appVersionLabel, "0.1.0"
        )
        XCTAssertEqual(
            try health(#"{"status":"ok","appVersion":"0.5.0","appBuild":" "}"#).appVersionLabel, "0.5.0"
        )
        XCTAssertEqual(
            try health(#"{"status":"ok","appVersion":"dev","appBuild":"dev"}"#).appVersionLabel, "dev"
        )
    }

    func testAMacThatNamesNoVersionGetsNone() throws {
        XCTAssertNil(try health(#"{"status":"ok"}"#).appVersionLabel)
        XCTAssertNil(try health(#"{"status":"ok","version":"","appVersion":" "}"#).appVersionLabel)
    }

    // MARK: - What the device shows

    private func probe() async -> Reachability {
        server.reply("/status", 200, #"{"state":"Loaded","loadedModelName":"Qwen3 4B","expertStreaming":false}"#)
        let client = ControlClient(
            config: ServerConfig(host: "127.0.0.1", port: server.port, token: "device-token")
        )
        return await ConnectivityProbe(transport: client).check()
    }

    func testConnectedToACurrentMacTheDeviceShowsItsVersionAndBuild() async {
        server.reply("/health", 200, Self.newMac)
        let ready = await probe()
        XCTAssertEqual(ready, .ready(version: "0.5.0 (157)", loadedModel: "Qwen3 4B"))
        XCTAssertEqual(ready.detail, "Silicon Optimizer 0.5.0 (157) — Qwen3 4B")
    }

    func testConnectedToAnOlderMacTheDeviceShowsWhatItSaid() async {
        server.reply("/health", 200, Self.oldMac)
        let ready = await probe()
        XCTAssertEqual(ready.detail, "Silicon Optimizer 0.1.0 — Qwen3 4B")
    }

    func testAMacWithoutHealthIsNotGivenAVersionCalledUnknown() async {
        // No /health reply: the loopback server answers 404, a route this Mac lacks.
        let ready = await probe()
        XCTAssertEqual(ready, .ready(version: nil, loadedModel: "Qwen3 4B"))
        XCTAssertEqual(ready.detail, "Silicon Optimizer — Qwen3 4B")
    }

    func testTheHandshakeReadsBothKindsOfMac() throws {
        let current = try JSONDecoder().decode(ControlAPI.Handshake.self, from: Data(
            #"{"port":51234,"pid":42,"token":"t","version":"0.5.0","appVersion":"0.5.0","appBuild":"157"}"#.utf8
        ))
        XCTAssertEqual(current.appVersion, "0.5.0")
        XCTAssertEqual(current.appBuild, "157")
        let older = try JSONDecoder().decode(ControlAPI.Handshake.self, from: Data(
            #"{"port":51234,"token":"t","version":"0.1.0"}"#.utf8
        ))
        XCTAssertNil(older.appVersion)
        XCTAssertEqual(older.version, "0.1.0")
    }
}
