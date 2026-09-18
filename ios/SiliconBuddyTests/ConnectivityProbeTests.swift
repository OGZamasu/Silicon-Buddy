import XCTest
@testable import SiliconBuddy

/// The probe exists to tell three failures apart. These are those three cases.
final class ConnectivityProbeTests: XCTestCase {

    func testAWorkingMac() async {
        let transport = StubTransport()
        transport.healthResult = .success(ControlAPI.Health(status: "ok", version: "0.1.0"))
        transport.statusResult = .success(
            ControlAPI.Status(
                state: "Loaded", loadedModelID: "bonsai-2-27b@PTQ1_0",
                loadedModelName: "Bonsai 2 27B", contextLength: 32768, expertStreaming: false
            )
        )
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .ready(version: "0.1.0", loadedModel: "Bonsai 2 27B"))
        XCTAssertTrue(result.isReady)
    }

    func testTheMacIsAwakeButTheAppIsClosed() async {
        let transport = StubTransport()
        transport.healthResult = .failure(TransportError.appNotRunning)
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .appNotRunning)
        XCTAssertEqual(result.headline, "App not running")
    }

    func testTheTailnetIsDown() async {
        let transport = StubTransport()
        transport.healthResult = .failure(TransportError.unreachable("100.64.0.1"))
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .unreachable("100.64.0.1"))
    }

    func testTheTokenWasRevoked() async {
        let transport = StubTransport()
        transport.healthResult = .success(ControlAPI.Health(status: "ok", version: "0.1.0"))
        transport.statusResult = .failure(TransportError.unauthorized)
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .unauthorized)
    }

    func testATimeoutReadsAsUnreachableRatherThanUnauthorized() async {
        let transport = StubTransport()
        transport.healthResult = .failure(TransportError.timedOut)
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .unreachable("the Mac"))
    }

    func testAMacWithoutHealthIsStillJudgedByItsToken() async {
        let transport = StubTransport()
        transport.healthResult = .failure(TransportError.routeUnavailable("/health"))
        transport.statusResult = .success(ControlAPI.Status(state: "Not loaded"))
        let result = await ConnectivityProbe(transport: transport).check()
        XCTAssertEqual(result, .ready(version: "unknown", loadedModel: "Not loaded"))
    }

    func testEveryStateSaysSomethingToThePerson() {
        let states: [Reachability] = [
            .unknown, .checking, .ready(version: "1", loadedModel: nil), .unauthorized,
            .appNotRunning, .unreachable("mac"), .failed("boom"),
        ]
        for state in states {
            XCTAssertFalse(state.headline.isEmpty)
            XCTAssertFalse(state.detail.isEmpty)
            XCTAssertFalse(state.symbol.isEmpty)
        }
    }
}
